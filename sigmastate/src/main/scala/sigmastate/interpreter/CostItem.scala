package sigmastate.interpreter

import sigmastate.{FixedCost, PerItemCost, SMethod, SType, TypeBasedCost}
import sigmastate.Values.{FixedCostValueCompanion, PerItemCostValueCompanion, ValueCompanion}
import sigmastate.lang.Terms.MethodCall

/** An item in the cost accumulation trace of a [[sigmastate.Values.ErgoTree]] evaluation. */
abstract class CostItem {
  def opName: String
  def cost: Int
}

/** An item in the cost accumulation trace of a [[sigmastate.Values.ErgoTree]] evaluation.
  * Represents cost of simple operation.
  * Used for debugging, testing and profiling of costing.
  * @param opDesc   descriptor of the ErgoTree operation
  * @param costKind kind of the cost to be added to accumulator
  */
case class FixedCostItem(opDesc: OperationDesc, costKind: FixedCost) extends CostItem {
  override def opName: String = opDesc.operationName
  override def cost: Int = costKind.cost
}
object FixedCostItem {
  def apply(companion: FixedCostValueCompanion): FixedCostItem = {
    FixedCostItem(companion.opDesc, companion.costKind)
  }
  def apply(method: SMethod, costKind: FixedCost): FixedCostItem = {
    FixedCostItem(MethodDesc(method), costKind)
  }
}

/** An item in the cost accumulation trace of a [[sigmastate.Values.ErgoTree]] evaluation.
  * Represents cost of an operation which depends on type (e.g. type of arguments).
  * Used for debugging, testing and profiling of costing.
  * @param opDesc   descriptor of the ErgoTree operation
  * @param costKind type based cost descriptor added to accumulator
  * @param tpe      concrete type on this the operation is executed
  * @see [[sigmastate.LE]], [[sigmastate.GT]]
  */
case class TypeBasedCostItem(
                                opDesc: OperationDesc,
                                costKind: TypeBasedCost,
                                tpe: SType) extends CostItem {
  override def opName: String = {
    val name = opDesc.operationName
    s"$name[$tpe]"
  }
  override def cost: Int = costKind.costFunc(tpe)
  override def equals(obj: Any): Boolean =
    (this eq obj.asInstanceOf[AnyRef]) || (obj != null && (obj match {
      case that: TypeBasedCostItem =>
        opDesc == that.opDesc && tpe == that.tpe
      case _ => false
    }))
  override def hashCode(): Int = 31 * opDesc.hashCode() + tpe.hashCode()
}
object TypeBasedCostItem {
  def apply(companion: ValueCompanion, tpe: SType): TypeBasedCostItem = {
    TypeBasedCostItem(companion.opDesc, companion.costKind.asInstanceOf[TypeBasedCost], tpe)
  }
}

/** An item in the cost accumulation trace of a [[sigmastate.Values.ErgoTree]] evaluation.
  * Represents cost of a sequence of operation.
  * Used for debugging, testing and profiling of costing.
  *
  * @param opDesc   descriptor of the ErgoTree operation
  * @param costKind descriptor of the cost added to accumulator
  * @param nItems   number of items in the sequence
  */
case class SeqCostItem(opDesc: OperationDesc, costKind: PerItemCost, nItems: Int)
    extends CostItem {
  override def opName: String = opDesc.operationName
  override def cost: Int = costKind.cost(nItems)
  /** How many data chunks in this cost item. */
  def chunks: Int = costKind.chunks(nItems)
}
object SeqCostItem {
  def apply(companion: PerItemCostValueCompanion, nItems: Int): SeqCostItem =
    SeqCostItem(companion.opDesc, companion.costKind, nItems)
}

/** An item in the cost accumulation trace of a [[sigmastate.Values.ErgoTree]] evaluation.
  * Represents cost of MethodCall operation.
  * Used for debugging, testing and profiling of costing.
  *
  * @param items cost details obtained as part of MethodCall evaluation
  */
case class MethodCallCostItem(items: CostDetails) extends CostItem {
  override def opName: String = MethodCall.typeName
  override def cost: Int = items.cost
}


