/**
 * Copyright(c) 2015 Knut Petter Meen, all rights reserved.
 */
package hipe.core.dsl

import hipe.HIPEOperations._
import hipe.core.States.TaskStates._
import hipe.core.dsl.StepDestinationCmd.{Goto, Next, Prev}
import org.slf4j.LoggerFactory

import scala.util.control.NoStackTrace
import scala.util.parsing.combinator._

object TransitionDSL {

  case class TransitionDSLError(message: String) extends Exception(message) with NoStackTrace

  /*
  Example of wanted behaviour:

  1. when task is completed go to next step
  2. when task is rejected go to previous step
  3. when task is <status> go to step <step_id>
  4. when task is completed go to step 1231


  TODO: ¡¡¡Challenge!!!
    When a "go to step "xyz"" statement is defined, referencing another step by ID.
    How to discover and handle removal of the referenced step?

    ANSWER:
    It's not really a problem, because the steps are stored with in the process,
    and whenever a step is removed, the rules of all other steps can be scanned
    for an ID that matches. The system should automatically assign to either
    next/prev step (depending on action).
*/
  object Parser extends JavaTokenParsers with TaskOperations {

    private val logger = LoggerFactory.getLogger(this.getClass)

    private[this] def taskSpec = "when task is" ~> taskStatus ^^ {
      case s: String if "approved".contentEquals(s) => Approved()
      case s: String if "rejected".contentEquals(s) => Rejected()
      case s: String if "consolidated".contentEquals(s) => Consolidated()
      case s: String if "closed".contentEquals(s) => Closed()
      case s =>
        logger.error(s"Unsupported task status: $s")
        throw new TransitionDSLError(s"Unsupported task status $s")
    }

    private[this] def goto = "go to" ~> (nextOrPrev | step) ^^ {
      case "next" => Next()
      case "previous" => Prev()
      case stepId => Goto(stepId)
    }

    private[this] def taskStatus = "approved" | "rejected" | "consolidated" | "closed"

    private[this] def step = "step" ~> stringLiteral

    private[this] def nextOrPrev = ("next" | "previous") <~ "step"

    def transition = taskSpec ~ goto

  }

}
