package sigmastate.utxo

import sigmastate.SCollection.SByteArray
import sigmastate.Values._
import sigmastate.lang.Terms._
import sigmastate._
import sigmastate.serialization.OpCodes.OpCode
import sigmastate.serialization.OpCodes
import org.ergoplatform.ErgoBox.RegisterId
import sigmastate.Operations._
import sigmastate.lang.exceptions.InterpreterException
import special.sigma.Box

/** Every operation is a transformer of some kind.
  * This trait is used merely to simplify implementation and avoid copy-paste.
  */
trait Transformer[IV <: SType, OV <: SType] extends NotReadyValue[OV] {
  val input: Value[IV]
}

/** Builds a new collection by applying a function to all elements of this collection.
  *
  * @param input  the collection to be mapped
  * @param mapper the function to apply to each element.
  * @tparam IV     the element type of the input collection.
  * @tparam OV     the element type of the returned collection.
  * @return       a new collection of type `Coll[OV]` resulting from applying the given function
  *                `mapper` to each element of this collection and collecting the results.
  */
case class MapCollection[IV <: SType, OV <: SType](
                                                    input: Value[SCollection[IV]],
                                                    mapper: Value[SFunc])
  extends Transformer[SCollection[IV], SCollection[OV]] {
  override def companion = MapCollection
  override val tpe = SCollection[OV](mapper.tpe.tRange.asInstanceOf[OV])
  override val opType = SCollection.MapMethod.stype.asFunc
}
object MapCollection extends ValueCompanion {
  override def opCode: OpCode = OpCodes.MapCollectionCode
}

/** Puts the elements of other collection `col2` after the elements of `input` collection
  * (concatenation of two collections).
  */
case class Append[IV <: SType](input: Value[SCollection[IV]], col2: Value[SCollection[IV]])
  extends Transformer[SCollection[IV], SCollection[IV]] {
  override def companion = Append
  override val tpe = input.tpe
  override val opType = SCollection.AppendMethod.stype
}
object Append extends ValueCompanion {
  override def opCode: OpCode = OpCodes.AppendCode
}

/** Selects an interval of elements.  The returned collection is made up
  *  of all elements `x` which satisfy the invariant:
  *  {{{
  *    from <= indexOf(x) < until
  *  }}}
  *  @param from   the lowest index to include from this collection.
  *  @param until  the lowest index to EXCLUDE from this collection.
  */
case class Slice[IV <: SType](input: Value[SCollection[IV]], from: Value[SInt.type], until: Value[SInt.type])
  extends Transformer[SCollection[IV], SCollection[IV]] {
  override def companion = Slice
  override val tpe = input.tpe
  override def opType = {
    val tpeColl = SCollection(input.tpe.typeParams.head.ident)
    SFunc(Array(tpeColl, SInt, SInt), tpeColl)
  }
}
object Slice extends ValueCompanion {
  override def opCode: OpCode = OpCodes.SliceCode
}

/** Selects all elements of `input` collection which satisfy the condition.
  *
  * @param input     the collection to be filtered
  * @param condition the predicate used to test elements.
  * @return a new collection consisting of all elements of this collection that satisfy
  *         the given `condition`. The order of the elements is preserved.
  */
case class Filter[IV <: SType](input: Value[SCollection[IV]],
                               condition: Value[SFunc])
  extends Transformer[SCollection[IV], SCollection[IV]] {
  override def companion = Filter
  override def tpe: SCollection[IV] = input.tpe
  override val opType = SCollection.FilterMethod.stype
}
object Filter extends ValueCompanion {
  override def opCode: OpCode = OpCodes.FilterCode
}

/** Transforms a collection of values to a boolean (see [[Exists]], [[ForAll]]). */
trait BooleanTransformer[IV <: SType] extends Transformer[SCollection[IV], SBoolean.type] {
  override val input: Value[SCollection[IV]]
  val condition: Value[SFunc]
  override def tpe = SBoolean
}
trait BooleanTransformerCompanion extends ValueCompanion {
  def argInfos: Seq[ArgInfo]
}

/** Tests whether a predicate holds for at least one element of this collection.
  *
  * @param input     the collection to be tested
  * @param condition the predicate used to test elements.
  * @return `true` if the given `condition` is satisfied by at least one element of this
  *         collection, otherwise `false`
  */
case class Exists[IV <: SType](override val input: Value[SCollection[IV]],
                               override val condition: Value[SFunc])
  extends BooleanTransformer[IV] {
  override def companion = Exists
  override val opType = SCollection.ExistsMethod.stype
}
object Exists extends BooleanTransformerCompanion {
  override def opCode: OpCode = OpCodes.ExistsCode
  override def argInfos: Seq[ArgInfo] = ExistsInfo.argInfos
}

/** Tests whether a predicate holds for all elements of this collection.
  *
  * @param input     the collection to be tested
  * @param condition the predicate used to test elements.
  * @return `true` if this collection is empty or the given `condition`
  *         holds for all elements of this collection, otherwise `false`.
  */
case class ForAll[IV <: SType](override val input: Value[SCollection[IV]],
                               override val condition: Value[SFunc])
  extends BooleanTransformer[IV] {
  override def companion = ForAll
  override val opType = SCollection.ForallMethod.stype
}
object ForAll extends BooleanTransformerCompanion {
  override def opCode: OpCode = OpCodes.ForAllCode
  override def argInfos: Seq[ArgInfo] = ForAllInfo.argInfos
}

/** Applies a binary function to a start value and all elements of this collection,
  * going left to right.
  *
  *  @param   input the collection to iterate
  *  @param   zero  the start value.
  *  @param   foldOp the binary function.
  *  @tparam  OV the result type of the binary operator.
  *  @return  the result of inserting `foldOp` between consecutive elements of this collection,
  *           going left to right with the start value `zero` on the left:
  *           {{{
  *             foldOp(...foldOp(zero, x_1), x_2, ..., x_n)
  *           }}}
  *           where `x_1, ..., x_n` are the elements of this collection.
  *           Returns `zero` if this collection is empty.
  */
case class Fold[IV <: SType, OV <: SType](input: Value[SCollection[IV]],
                                          zero: Value[OV],
                                          foldOp: Value[SFunc])
  extends Transformer[SCollection[IV], OV] {
  override def companion = Fold
  implicit override def tpe: OV = zero.tpe
  val opType: SFunc = SCollection.FoldMethod.stype
}

object Fold extends ValueCompanion {
  override def opCode: OpCode = OpCodes.FoldCode
  def sum[T <: SNumericType](input: Value[SCollection[T]], varId: Int)(implicit tT: T) =
    Fold(input,
      Constant(tT.upcast(0.toByte), tT),
      FuncValue(Array((varId, STuple(tT, tT))),
        Plus(
          SelectField(ValUse(varId, STuple(tT, tT)), 1).asNumValue,
          SelectField(ValUse(varId, STuple(tT, tT)), 2).asNumValue))
    )
}

/** The element of the collection or default value.
  * If an index is out of bounds (`i < 0 || i >= length`) then `default` value is returned.
  *
  * @param  input the zero-based indexed collection
  * @param  index the index of the requested element (zero-based)
  * @tparam V the type of elements
  * @return the element at the given index or `default` value if index is out or bounds
  * @throws ArrayIndexOutOfBoundsException if `index < 0` or `length <= index`
  */
case class ByIndex[V <: SType](input: Value[SCollection[V]],
                               index: Value[SInt.type],
                               default: Option[Value[V]] = None)
  extends Transformer[SCollection[V], V] with NotReadyValue[V] {
  override def companion = ByIndex
  override val tpe = input.tpe.elemType
  override val opType = SCollection.ApplyMethod.stype.asFunc
}
object ByIndex extends ValueCompanion {
  override def opCode: OpCode = OpCodes.ByIndexCode
}

/** Select tuple field by its 1-based index. E.g. input._1 is transformed to
  * SelectField(input, 1)
  */
case class SelectField(input: Value[STuple], fieldIndex: Byte)
  extends Transformer[STuple, SType] with NotReadyValue[SType] {
  override def companion = SelectField
  override val tpe = input.tpe.items(fieldIndex - 1)
  override val opType = SFunc(input.tpe, tpe)
}
object SelectField extends ValueCompanion {
  override def opCode: OpCode = OpCodes.SelectFieldCode
  def typed[T <: SValue](input: Value[STuple], fieldIndex: Byte): T = {
    SelectField(input, fieldIndex).asInstanceOf[T]
  }
}

/** Represents execution of Sigma protocol that validates the given input SigmaProp. */
case class SigmaPropIsProven(input: Value[SSigmaProp.type])
  extends Transformer[SSigmaProp.type, SBoolean.type] with NotReadyValueBoolean {
  override def companion = SigmaPropIsProven
  override val opType = SFunc(input.tpe, SBoolean)
}
object SigmaPropIsProven extends ValueCompanion {
  override def opCode: OpCode = OpCodes.SigmaPropIsProvenCode
}

/** Extract serialized bytes of a SigmaProp value */
case class SigmaPropBytes(input: Value[SSigmaProp.type])
  extends Transformer[SSigmaProp.type, SByteArray] with NotReadyValue[SByteArray] {
  override def companion = SigmaPropBytes
  override def tpe = SByteArray
  override val opType = SFunc(input.tpe, tpe)
}
object SigmaPropBytes extends ValueCompanion {
  override def opCode: OpCode = OpCodes.SigmaPropBytesCode
}
trait SimpleTransformerCompanion extends ValueCompanion {
  def argInfos: Seq[ArgInfo]
}

/** The length of the collection (aka size). */
case class SizeOf[V <: SType](input: Value[SCollection[V]])
  extends Transformer[SCollection[V], SInt.type] with NotReadyValueInt {
  override def companion = SizeOf
  override def opType = SizeOf.OpType
}
object SizeOf extends SimpleTransformerCompanion {
  val OpType = SFunc(SCollection(SType.tIV), SInt)
  override def opCode: OpCode = OpCodes.SizeOfCode
  override def argInfos: Seq[ArgInfo] = SizeOfInfo.argInfos
}

sealed trait Extract[V <: SType] extends Transformer[SBox.type, V] {
}

/** Extracts the monetary value, in Ergo tokens (NanoErg unit of measure) from input Box. */
case class ExtractAmount(input: Value[SBox.type]) extends Extract[SLong.type] with NotReadyValueLong {
  override def companion = ExtractAmount
  override def opType = ExtractAmount.OpType
}
object ExtractAmount extends SimpleTransformerCompanion {
  val OpType = SFunc(SBox, SLong)
  override def opCode: OpCode = OpCodes.ExtractAmountCode
  override def argInfos: Seq[ArgInfo] = ExtractAmountInfo.argInfos
}

/** Extract serialized bytes of guarding script.
  * As a reminder, the script should be evaluated to true in order to
  * open this box. (aka spend it in a transaction).
  */
case class ExtractScriptBytes(input: Value[SBox.type]) extends Extract[SByteArray] with NotReadyValueByteArray {
  override def companion = ExtractScriptBytes
  override def opType = ExtractScriptBytes.OpType
}
object ExtractScriptBytes extends SimpleTransformerCompanion {
  val OpType = SFunc(SBox, SByteArray)
  override def opCode: OpCode = OpCodes.ExtractScriptBytesCode
  override def argInfos: Seq[ArgInfo] = ExtractScriptBytesInfo.argInfos
}

/** Extracts serialized bytes of this box's content, including proposition bytes. */
case class ExtractBytes(input: Value[SBox.type]) extends Extract[SByteArray] with NotReadyValueByteArray {
  override def companion = ExtractBytes
  override def opType = ExtractBytes.OpType
}
object ExtractBytes extends SimpleTransformerCompanion {
  val OpType = SFunc(SBox, SByteArray)
  override def opCode: OpCode = OpCodes.ExtractBytesCode
  override def argInfos: Seq[ArgInfo] = ExtractBytesInfo.argInfos
}

/** Extracts serialized bytes of this box's content, excluding transactionId and index of output. */
case class ExtractBytesWithNoRef(input: Value[SBox.type]) extends Extract[SByteArray] with NotReadyValueByteArray {
  override def companion = ExtractBytesWithNoRef
  override def opType = ExtractBytesWithNoRef.OpType
}
object ExtractBytesWithNoRef extends SimpleTransformerCompanion {
  val OpType = SFunc(SBox, SByteArray)
  override def opCode: OpCode = OpCodes.ExtractBytesWithNoRefCode
  override def argInfos: Seq[ArgInfo] = ExtractBytesWithNoRefInfo.argInfos
}

/** Extracts Blake2b256 hash of this box's content, basically equals to `blake2b256(bytes)` */
case class ExtractId(input: Value[SBox.type]) extends Extract[SByteArray] with NotReadyValueByteArray {
  override def companion = ExtractId
  override def opType = ExtractId.OpType
}
object ExtractId extends SimpleTransformerCompanion {
  val OpType = SFunc(SBox, SByteArray)
  override def opCode: OpCode = OpCodes.ExtractIdCode
  override def argInfos: Seq[ArgInfo] = ExtractIdInfo.argInfos
}

/** See [[Box.getReg()]]*/
case class ExtractRegisterAs[V <: SType]( input: Value[SBox.type],
                                          registerId: RegisterId,
                                          override val tpe: SOption[V])
  extends Extract[SOption[V]] with NotReadyValue[SOption[V]] {
  override def companion = ExtractRegisterAs
  override val opType = SFunc(ExtractRegisterAs.BoxAndByte, tpe)
}
object ExtractRegisterAs extends ValueCompanion {
  override def opCode: OpCode = OpCodes.ExtractRegisterAs

  //HOTSPOT:: avoids thousands of allocations per second
  private val BoxAndByte: IndexedSeq[SType] = Array(SBox, SByte)

  def apply[V <: SType](input: Value[SBox.type],
                        registerId: RegisterId)(implicit tpe: V): ExtractRegisterAs[V] =
    ExtractRegisterAs(input, registerId, SOption(tpe))
}

/**
  * Tuple of height when block got included into the blockchain and transaction identifier with box index in the transaction outputs serialized to the byte array.
  * @param input box
  */
case class ExtractCreationInfo(input: Value[SBox.type]) extends Extract[STuple] with NotReadyValue[STuple] {
  import ExtractCreationInfo._
  override def companion = ExtractCreationInfo
  override def tpe: STuple = ResultType
  override def opType = OpType
}
object ExtractCreationInfo extends SimpleTransformerCompanion {
  override def opCode: OpCode = OpCodes.ExtractCreationInfoCode
  override def argInfos: Seq[ArgInfo] = ExtractCreationInfoInfo.argInfos
  val ResultType = STuple(SInt, SByteArray)
  val OpType = SFunc(SBox, ResultType)
}

trait Deserialize[V <: SType] extends NotReadyValue[V]

/** Extracts context variable as Coll[Byte], deserializes it to script and then executes this script in the current context.
  * The original `Coll[Byte]` of the script is available as `getVar[Coll[Byte]](id)`
  * @param id identifier of the context variable
  * @tparam V result type of the deserialized script.
  * @throws InterpreterException if the actual script type doesn't conform to T
  * @return result of the script execution in the current context
  * @since 2.0
  */
case class DeserializeContext[V <: SType](id: Byte, tpe: V) extends Deserialize[V] {
  override def companion = DeserializeContext
  override val opType = SFunc(Array(SContext, SByte), tpe)
}
object DeserializeContext extends ValueCompanion {
  override def opCode: OpCode = OpCodes.DeserializeContextCode
}

/** Extract register of SELF box as Coll[Byte], deserialize it into Value and inline into executing script.
  * NOTE: it only applicable to SELF box
  */
case class DeserializeRegister[V <: SType](reg: RegisterId, tpe: V, default: Option[Value[V]] = None) extends Deserialize[V] {
  override def companion = DeserializeRegister
  override val opType = SFunc(Array(SBox, SByte, SOption(tpe)), tpe)
}
object DeserializeRegister extends ValueCompanion {
  override def opCode: OpCode = OpCodes.DeserializeRegisterCode
}

/** See [[special.sigma.Context.getVar()]] for detailed description. */
case class GetVar[V <: SType](varId: Byte, override val tpe: SOption[V]) extends NotReadyValue[SOption[V]] {
  override def companion = GetVar
  override val opType = SFunc(Array(SContext, SByte), tpe)
}
object GetVar extends ValueCompanion {
  override def opCode: OpCode = OpCodes.GetVarCode
  def apply[V <: SType](varId: Byte, innerTpe: V): GetVar[V] = GetVar[V](varId, SOption(innerTpe))
}

/** Returns the option's value.
  *
  *  @note The option must be nonempty.
  *  @throws java.util.NoSuchElementException if the option is empty.
  */
case class OptionGet[V <: SType](input: Value[SOption[V]]) extends Transformer[SOption[V], V] {
  override def companion = OptionGet
  override val opType = SFunc(input.tpe, tpe)
  override def tpe: V = input.tpe.elemType
  override def toString: String = s"$input.get"
}
object OptionGet extends SimpleTransformerCompanion {
  override def opCode: OpCode = OpCodes.OptionGetCode
  override def argInfos: Seq[ArgInfo] = OptionGetInfo.argInfos
}

/** Returns the option's value if the option is nonempty, otherwise
  * return the result of evaluating `default`.
  * NOTE: the `default` is evaluated even if the option contains the value
  * i.e. not lazily.
  *
  *  @param default  the default expression.
  */
case class OptionGetOrElse[V <: SType](input: Value[SOption[V]], default: Value[V])
  extends Transformer[SOption[V], V] {
  override def companion = OptionGetOrElse
  override val opType = SFunc(IndexedSeq(input.tpe, tpe), tpe)
  override def tpe: V = input.tpe.elemType
}
object OptionGetOrElse extends ValueCompanion {
  override def opCode: OpCode = OpCodes.OptionGetOrElseCode
}

/** Returns false if the option is None, true otherwise. */
case class OptionIsDefined[V <: SType](input: Value[SOption[V]])
  extends Transformer[SOption[V], SBoolean.type] {
  override def companion = OptionIsDefined
  override val opType = SFunc(input.tpe, SBoolean)
  override def tpe= SBoolean
}
object OptionIsDefined extends SimpleTransformerCompanion {
  override def opCode: OpCode = OpCodes.OptionIsDefinedCode
  override def argInfos: Seq[ArgInfo] = OptionIsDefinedInfo.argInfos
}
