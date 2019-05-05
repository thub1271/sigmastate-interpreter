package org.ergoplatform

import sigmastate.Values.SValue
import sigmastate.lang.exceptions.{SerializerException, InterpreterException, InvalidOpCode}
import sigmastate.serialization.OpCodes.OpCode
import sigmastate.serialization.{ValueSerializer, OpCodes}
import sigmastate.utxo.DeserializeContext
import sigma.util.Extensions.ByteOps

sealed trait RuleStatus
case object EnabledRule extends RuleStatus
case object DisabledRule extends RuleStatus
case class  ReplacedRule(newRuleId: Short) extends RuleStatus
case class  ChangedRule(newValue: Array[Byte]) extends RuleStatus

final case class InvalidResult(errors: Seq[Throwable])

case class ValidationRule(
  id: Short,
  description: String,
  status: RuleStatus
) {
  protected def validate[T](vs: ValidationSettings, condition: => Boolean, error: => Throwable)(block: => T): T = {
    val status = vs.getStatus(this.id)
    status match {
      case Some(DisabledRule) => block
      case Some(EnabledRule) =>
        if (!condition) {
          throw error
        } else
          block
      case Some(r: ReplacedRule) =>
        throw new ReplacedRuleException(vs, this, r)
      case Some(r: ChangedRule) =>
        throw new ChangedRuleException(vs, this, r)
      case None =>
        throw new InterpreterException(
          s"ValidationRule $this not found in validation settings")
    }
  }
}
object ValidationRule {
  def error(msg: String): InvalidResult = InvalidResult(Seq(new SoftForkException(msg)))
}

/** Instances of this class are used as messages to communicate soft-fork information,
  * from the context where the soft-fork condition is detected (such as in ValidationRules),
  * up the stack to the point where it is clear how to handle it.
  * Some messages of this kind are not handled, it which case a new Exception is thrown
  * and this instance should be attached as `cause` parameter. */
class SoftForkException(message: String) extends Exception(message) {
  /** This override is required as an optimization to avoid spending time on recording stack trace.
    * This makes throwing exceptions almost as fast as usual return of a method.
    */
  override def fillInStackTrace(): Throwable = this
}

case class ReplacedRuleException(vs: ValidationSettings, replacedRule: ValidationRule, replacement: ReplacedRule)
  extends SoftForkException(s"Rule ${replacedRule.id} was replaced with ${replacement.newRuleId}")

case class ChangedRuleException(vs: ValidationSettings, changedRule: ValidationRule, change: ChangedRule)
  extends SoftForkException(s"Rule ${changedRule.id} was changed with ${change}")

/**
  * Configuration of validation.
  */
abstract class ValidationSettings {
  def get(id: Short): Option[ValidationRule]
  def getStatus(id: Short): Option[RuleStatus]
}

class MapValidationSettings(map: Map[Short, ValidationRule]) extends ValidationSettings {
  override def get(id: Short): Option[ValidationRule] = map.get(id)
  override def getStatus(id: Short): Option[RuleStatus] = map.get(id).map(_.status)
}

object ValidationRules {

  val CheckDeserializedScriptType = new ValidationRule(1000,
    "Deserialized script should have expected type", EnabledRule) {
    def apply[T](vs: ValidationSettings, d: DeserializeContext[_], script: SValue)(block: => T): T = {
      validate(vs, d.tpe == script.tpe,
        new InterpreterException(s"Failed context deserialization of $d: \n" +
        s"expected deserialized script to have type ${d.tpe}; got ${script.tpe}"))(block)
    }
  }

  val CheckDeserializedScriptIsSigmaProp = new ValidationRule(1001,
    "Deserialized script should have SigmaProp type", EnabledRule) {
    def apply[T](vs: ValidationSettings, root: SValue)(block: => T): T = {
      validate(vs, root.tpe.isSigmaProp, new SerializerException(
        s"Failed deserialization, expected deserialized script to have type SigmaProp; got ${root.tpe}")
        )(block)
    }
  }

  val CheckValidOpCode = new ValidationRule(1002,
    "There should be registered serializer for each OpCode", EnabledRule) {
    def apply[T](vs: ValidationSettings, ser: ValueSerializer[_], opCode: OpCode)(block: => T): T = {
      def msg = s"Cannot find serializer for Value with opCode = LastConstantCode + ${opCode.toUByte - OpCodes.LastConstantCode}"
      try validate(vs, ser != null && ser.opCode == opCode, new InvalidOpCode(msg))(block)
      catch { case e: ChangedRuleException =>
        if (e.change.newValue.contains(opCode)) throw e
        else throw new InvalidOpCode(msg, None, Some(e))
      }
    }
  }

  val ruleSpecs: Seq[ValidationRule] = Seq(
    CheckDeserializedScriptType,
    CheckDeserializedScriptIsSigmaProp,
    CheckValidOpCode
  )

  /** Validation settings that correspond to the latest version of the ErgoScript. */
  val currentSettings: ValidationSettings = new MapValidationSettings(
    ruleSpecs.map(r => r.id -> r).toMap
  )

}
