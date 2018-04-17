package sigmastate.utxo

import java.io.{File, FileWriter, PrintWriter}

import org.scalacheck.Gen
import org.scalatest.{Matchers, PropSpec}
import org.scalatest.prop.{GeneratorDrivenPropertyChecks, PropertyChecks}
import scorex.crypto.authds.{ADDigest, ADKey, ADValue}
import scorex.crypto.authds.avltree.batch.{BatchAVLProver, Insert, Remove}
import scorex.crypto.hash.{Blake2b256, Blake2b256Unsafe, Digest32}
import sigmastate.Values.IntConstant
import sigmastate.interpreter.ContextExtension
import sigmastate.utxo.ErgoBox.R3
import sigmastate.{AvlTreeData, GE}

import scala.annotation.tailrec
import scala.collection.mutable
import scala.util.Try


class BlockchainSimulationSpecification extends PropSpec
  with PropertyChecks
  with GeneratorDrivenPropertyChecks
  with Matchers {

  private lazy val hash = Blake2b256

  class InMemoryErgoBoxReader(prover: ValidationState.BatchProver) extends ErgoBoxReader {
    private val boxes = mutable.Map[ErgoBox.BoxId, ErgoBox]()

    override def byId(boxId: ADKey): Try[ErgoBox] = Try(boxes(boxId))

    def byR3Value(i: Int): Iterable[ErgoBox] =
      boxes.values.filter(_.get(R3).getOrElse(IntConstant(i + 1)) == IntConstant(i))

    def allIds: Iterable[ErgoBox.BoxId] = boxes.keys

    def applyBlock(block: Block): Unit = {
      val toRemove = block.txs.flatMap(_.inputs).map(_.boxId)
      toRemove.foreach(k => prover.performOneOperation(Remove(k)))
      toRemove.foreach(k => boxes.remove(k))

      val toAdd = block.txs.flatMap(_.outputs)
      toAdd.foreach(b => prover.performOneOperation(Insert(b.id, ADValue @@ b.bytes)))
      toAdd.foreach(b => boxes.put(b.id, b))

      prover.generateProof()
    }

    def digest: ADDigest = prover.digest
  }

  case class Block(txs: IndexedSeq[ErgoTransaction])

  case class ValidationState(state: BlockchainState, boxesReader: InMemoryErgoBoxReader) {
    def applyBlock(block: Block): Try[ValidationState] = Try {
      val height = state.currentHeight + 1

      block.txs.foreach { tx =>
        ErgoTransactionValidator.validate(tx, state.copy(currentHeight = height), boxesReader) match {
          case Left(throwable) => throw throwable
          case Right(_) =>
        }
      }

      boxesReader.applyBlock(block)
      val newState = BlockchainState(height, state.lastBlockUtxoRoot.copy(startingDigest = boxesReader.digest))
      ValidationState(newState, boxesReader)
    }
  }

  object ValidationState {
    type BatchProver = BatchAVLProver[Digest32, Blake2b256Unsafe]

    val initBlock = Block {
      (1 to 20).map { i =>
        val txId = hash.hash(i.toString.getBytes ++ scala.util.Random.nextString(12).getBytes)
        val boxes = (1 to 50).map(_ => ErgoBox(10, GE(Height, IntConstant(i)), Map(R3 -> IntConstant(i)), txId))
        ErgoTransaction(IndexedSeq(), boxes)
      }
    }

    val bigBlock = Block {
      (1 to 400).map { i =>
        val txId = hash.hash(i.toString.getBytes ++ scala.util.Random.nextString(12).getBytes)
        val boxes = (1 to 500).map(_ => ErgoBox(10, GE(Height, IntConstant(i)), Map(R3 -> IntConstant(i)), txId))
        ErgoTransaction(IndexedSeq(), boxes)
      }
    }

    def initialState(block: Block = initBlock): ValidationState = {
      val keySize = 32
      val prover = new BatchProver(keySize, None)

      val digest = prover.digest
      val utxoRoot = AvlTreeData(digest, keySize)

      val bs = BlockchainState(currentHeight = 0, utxoRoot)

      val boxReader = new InMemoryErgoBoxReader(prover)

      ValidationState(bs, boxReader).applyBlock(block).get
    }
  }


  def generateBlock(state: ValidationState, miner: ErgoProvingInterpreter, height: Int = 1): Block = {
    val minerPubKey = miner.dlogSecrets.head.publicImage
    val boxesToSpend = state.boxesReader.byR3Value(height)
    val txs = boxesToSpend.map { box =>
      val newBoxCandidate = new ErgoBoxCandidate(10, minerPubKey)
      val fakeInput = UnsignedInput(box.id)
      val tx = UnsignedErgoTransaction(IndexedSeq(fakeInput), IndexedSeq(newBoxCandidate))
      val context = ErgoContext(height + 1,
        state.state.lastBlockUtxoRoot,
        IndexedSeq(box),
        tx,
        box,
        ContextExtension.empty)
      val proverResult = miner.prove(box.proposition, context, tx.messageToSign).get

      tx.toSigned(IndexedSeq(proverResult))
    }.toIndexedSeq

    Block(txs)
  }

  property("apply one valid block") {
    val state = ValidationState.initialState()
    val miner = new ErgoProvingInterpreter()
    val block = generateBlock(state, miner)
    val updStateTry = state.applyBlock(block)
    updStateTry.isSuccess shouldBe true
  }

  property("apply many blocks") {
    val state = ValidationState.initialState()
    val miner = new ErgoProvingInterpreter()

    @tailrec
    def checkState(state: ValidationState,
                   miner: ErgoProvingInterpreter,
                   currentLevel: Int,
                   limit: Int): Unit = currentLevel match {
      case i if i >= limit => ()
      case _ =>
        val block = generateBlock(state, miner, currentLevel + 1)
        val updStateTry = state.applyBlock(block)
        updStateTry.isSuccess shouldBe true
        checkState(updStateTry.get, miner, currentLevel + 1, limit)
    }

    val randomDeepness = Gen.chooseNum(10, 20).sample.getOrElse(15)
    checkState(state, miner, 0, randomDeepness)
  }

  def bench(numberOfBlocks: Int) = {
    property(s"apply $numberOfBlocks blocks benchmark") {
      val state = ValidationState.initialState(ValidationState.bigBlock)
      val miner = new ErgoProvingInterpreter()
      val blocks = (0 to numberOfBlocks - 1).map { generateBlock(state, miner, _) }
      val start = System.currentTimeMillis()

      blocks.foldLeft(state: ValidationState) { case (s, b) =>
        val updStateTry = s.applyBlock(b)
        updStateTry.isSuccess shouldBe true
        updStateTry.get
      }

      val end = System.currentTimeMillis()
      val time = end - start

      println(s"Total time for $numberOfBlocks blocks: $time ms")
      printResults(numberOfBlocks, time)
    }
  }

  bench(100)
  bench(200)
  bench(300)
  bench(400)


  def printResults(number: Int, time: Long): Unit = {
    val file = new File("target/bench")
    file.mkdirs()
    val writer = new FileWriter(s"target/bench/result$number.csv", false)
    writer.write("time\n")
    writer.write(s"$time\n")
    writer.flush
    writer.close
  }

}
