package sigmastate.interpreter

import scala.collection.mutable

/** Abstract representation of cost results obtained during evaluation. */
abstract class CostDetails {
  /** The total cost of evaluation. */
  def cost: Int
  /** The trace of costed operations performed during evaluation. */
  def trace: Seq[CostItem]
  /** Actual execution time (in nanoseconds) if defined. */
  def actualTimeNano: Option[Long]
}

/** Detailed results of cost evaluation represented by trace.
  * NOTE: the `trace` is obtained during execution of [[ErgoTree]] operations.
  * @param trace accumulated trace of all cost items (empty for AOT costing)
  * @param actualTimeNano measured time of execution (if some)
  */
case class TracedCost(trace: Seq[CostItem],
                      actualTimeNano: Option[Long] = None) extends CostDetails {
  /** Total cost of all cost items. */
  def cost: Int = trace.foldLeft(0)(_ + _.cost)
}

/** Result of cost evaluation represented using simple given value.
  * Used to represent cost of AOT costing.
  * @param cost the given value of the total cost
  * @param actualTimeNano measured time of execution (if some)
  */
case class GivenCost(cost: Int,
                     actualTimeNano: Option[Long] = None) extends CostDetails {
  /** The trait is empty for this representation of CostDetails.
    */
  override def trace: Seq[CostItem] = mutable.WrappedArray.empty
}

object CostDetails {
  /** Empty sequence of cost items. Should be used whenever possible to avoid allocations. */
  val EmptyTrace: Seq[CostItem] = mutable.WrappedArray.empty

  /** CostDetails with empty trace have also zero total cost. */
  val ZeroCost = TracedCost(EmptyTrace)

  /** Helper factory method to create CostDetails from the given trace. */
  def apply(trace: Seq[CostItem]): CostDetails = TracedCost(trace)

  /** Helper recognizer to work with different representations of costs in patterns
    * uniformly.
    */
  def unapply(d: CostDetails): Option[(Int, Seq[CostItem])] = d match {
    case TracedCost(t, _) => Some((d.cost, t))
    case GivenCost(c, _) => Some((c, EmptyTrace))
    case _ => None
  }
}

