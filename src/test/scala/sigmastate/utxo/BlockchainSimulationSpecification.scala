package sigmastate.utxo

import java.io.{File, FileWriter}

import org.ergoplatform
import org.scalacheck.Gen
import org.scalatest.prop.{GeneratorDrivenPropertyChecks, PropertyChecks}
import org.scalatest.{Matchers, PropSpec}
import scorex.crypto.authds.avltree.batch.{BatchAVLProver, Insert, Remove}
import scorex.crypto.authds.{ADDigest, ADKey, ADValue}
import scorex.crypto.hash.{Blake2b256, Digest32}
import sigmastate.Values.LongConstant
import sigmastate.helpers.ErgoLikeProvingInterpreter
import sigmastate.interpreter.ContextExtension
import org.ergoplatform.ErgoBox.R3
import org.ergoplatform._
import sigmastate.{AvlTreeData, GE}

import scala.annotation.tailrec
import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.util.Try


class BlockchainSimulationSpecification extends PropSpec
  with PropertyChecks
  with GeneratorDrivenPropertyChecks
  with Matchers {

  import BlockchainSimulationSpecification._

  def generateBlock(state: ValidationState, miner: ErgoLikeProvingInterpreter, height: Int): Block = {
    val minerPubKey = miner.dlogSecrets.head.publicImage
    val boxesToSpend = state.boxesReader.byR3Value(height)

    val txs = boxesToSpend.map { box =>
      val newBoxCandidate = new ErgoBoxCandidate(10, minerPubKey, Map(R3 -> LongConstant(height + windowSize)))
      val unsignedInput = new UnsignedInput(box.id)
      val tx = UnsignedErgoLikeTransaction(IndexedSeq(unsignedInput), IndexedSeq(newBoxCandidate))
      val context = ErgoLikeContext(height + 1,
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
    val miner = new ErgoLikeProvingInterpreter()
    val block = generateBlock(state, miner, 1)
    val updStateTry = state.applyBlock(block)
    updStateTry.isSuccess shouldBe true
  }

  property("too costly block") {
    val state = ValidationState.initialState()
    val miner = new ErgoLikeProvingInterpreter()
    val block = generateBlock(state, miner, 1)
    val updStateTry = state.applyBlock(block, maxCost = 1)
    updStateTry.isSuccess shouldBe false
  }

  property("apply many blocks") {
    val state = ValidationState.initialState()
    val miner = new ErgoLikeProvingInterpreter()

    @tailrec
    def checkState(state: ValidationState,
                   miner: ErgoLikeProvingInterpreter,
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

  ignore(s"benchmarking applying many blocks") {
    val results = new TrieMap[Int, Long]

    def bench(numberOfBlocks: Int): Unit = {

      val state = ValidationState.initialState()
      val miner = new ErgoLikeProvingInterpreter()

      val (_, time) = (0 until numberOfBlocks).foldLeft(state -> 0L) { case ((s, timeAcc), h) =>
        val b = generateBlock(state, miner, h)

        val t0 = System.currentTimeMillis()
        val updStateTry = s.applyBlock(b)
        val t = System.currentTimeMillis()

        updStateTry.isSuccess shouldBe true
        updStateTry.get -> (timeAcc + (t - t0))
      }

      println(s"Total time for $numberOfBlocks blocks: $time ms")
      results.put(numberOfBlocks, time)
    }

    bench(100)
    bench(200)
    bench(300)
    bench(400)

    printResults(results.toMap)

    def printResults(results: Map[Int, Long]): Unit = {
      val file = new File("target/bench")
      file.mkdirs()
      val writer = new FileWriter(s"target/bench/result.csv", false)
      val sorted = results.toList.sortBy { case (i, _) => i }
      val header = sorted.map(_._1).mkString(",")
      writer.write(s"$header\n")
      val values = sorted.map(_._2).mkString(",")
      writer.write(s"$values\n")
      writer.flush()
      writer.close()
    }
  }
}

object BlockchainSimulationSpecification {
  private lazy val hash = Blake2b256

  val windowSize = 10

  val MaxBlockCost = 700000

  case class Block(txs: IndexedSeq[ErgoLikeTransaction])

  class InMemoryErgoBoxReader(prover: ValidationState.BatchProver) extends ErgoBoxReader {
    private type KeyType = mutable.WrappedArray.ofByte
    private def getKey(id: Array[Byte]): KeyType = new mutable.WrappedArray.ofByte(id)
    private val boxes = mutable.Map[KeyType, ErgoBox]()

    override def byId(boxId: ADKey): Try[ErgoBox] = byId(getKey(boxId))
    def byId(boxId: KeyType): Try[ErgoBox] = Try(boxes(boxId))

    def byR3Value(i: Int): Iterable[ErgoBox] =
      boxes.values.filter(_.get(R3).getOrElse(LongConstant(i + 1)) == LongConstant(i))

    def byTwoInts(r1Id: ErgoBox.RegisterIdentifier, int1: Int,
                  r2Id: ErgoBox.RegisterIdentifier, int2: Int): Option[ErgoBox] =
      boxes.values.find { box =>
        box.get(r1Id).getOrElse(LongConstant(int1 + 1)) == LongConstant(int1) &&
          box.get(r2Id).getOrElse(LongConstant(int2 + 1)) == LongConstant(int2)
      }

    def allIds: Iterable[KeyType] = boxes.keys

    def applyBlock(block: Block): Unit = {
      val toRemove = block.txs.flatMap(_.inputs).map(_.boxId)
      toRemove.foreach(k => prover.performOneOperation(Remove(k)))
      toRemove.foreach(k => boxes.remove(getKey(k)))

      val toAdd = block.txs.flatMap(_.outputs)
      toAdd.foreach(b => prover.performOneOperation(Insert(b.id, ADValue @@ b.bytes)))
      toAdd.foreach(b => boxes.put(getKey(b.id), b))

      prover.generateProof()
    }

    def digest: ADDigest = prover.digest
  }


  case class ValidationState(state: BlockchainState, boxesReader: InMemoryErgoBoxReader) {
    def applyBlock(block: Block, maxCost: Int = MaxBlockCost): Try[ValidationState] = Try {
      val height = state.currentHeight + 1

      val blockCost = block.txs.foldLeft(0L) {case (accCost, tx) =>
        ErgoTransactionValidator.validate(tx, state.copy(currentHeight = height), boxesReader) match {
          case Left(throwable) => throw throwable
          case Right(cost) => accCost + cost
        }
      }

      assert(blockCost <= maxCost, s"Block cost $blockCost exceeds limit $maxCost")

      boxesReader.applyBlock(block)
      val newState = BlockchainState(height, state.lastBlockUtxoRoot.copy(startingDigest = boxesReader.digest))
      ValidationState(newState, boxesReader)
    }
  }

  object ValidationState {
    type BatchProver = BatchAVLProver[Digest32, Blake2b256.type]

    val initBlock = Block {
      (1 to windowSize).map { i =>
        val txId = hash.hash(i.toString.getBytes ++ scala.util.Random.nextString(12).getBytes)
        val boxes = (1 to 50).map(_ => ErgoBox(10, GE(Height, LongConstant(i)), Map(R3 -> LongConstant(i)), txId))
        ergoplatform.ErgoLikeTransaction(IndexedSeq(), boxes)
      }
    }

    def initialState(block: Block = initBlock): ValidationState = {
      val keySize = 32
      val prover = new BatchProver(keySize, None)

      val digest = prover.digest
      val utxoRoot = AvlTreeData(digest, keySize)

      val bs = BlockchainState(currentHeight = -2, utxoRoot)

      val boxReader = new InMemoryErgoBoxReader(prover)

      ValidationState(bs, boxReader).applyBlock(block).get
    }
  }
}