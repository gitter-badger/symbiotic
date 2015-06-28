/**
 * Copyright(c) 2015 Knut Petter Meen, all rights reserved.
 */
package hipe

import hipe.core.FailureTypes._
import hipe.core.States.{AssignmentStates, TaskStates}
import hipe.core._
import hipe.core.dsl.StepDestinationCmd.{Goto, Next, Prev}
import models.base.PersistentType.{UserStamp, VersionStamp}
import models.parties.UserId
import org.slf4j.LoggerFactory

object HIPEOperations {

  /**
   * Functions that perform computations on Process data
   */
  trait ProcessOperations {

    private val logger = LoggerFactory.getLogger(classOf[ProcessOperations])

    /**
     * Appends a Step at the end of the process in a new separate StepGroup.
     *
     * @param proc the Process to append a step to
     * @param step the Step to append
     * @return the Process with the appended Step
     */
    private[hipe] def appendStep(proc: Process, step: Step): Process =
      proc.copy(stepGroups = proc.stepGroups ::: StepGroupList(StepGroup.create(step)))

    /**
     * Will append a Step to the end of the specified StepGroup.
     *
     * @param proc the Process
     * @param sgid the StepGroupId where the Step should be appended
     * @param step the Step to append
     * @return HIPEResult with the updated Process config
     */
    private[hipe] def appendStepToGroup(proc: Process, sgid: StepGroupId, step: Step): HIPEResult[Process] =
      proc.stepGroups.findWithPosition(sgid).map {
        case (sg: StepGroup, idx: Int) =>
          val nsg = sg.copy(steps = sg.steps ::: StepList(step))
          Right(proc.copy(stepGroups = proc.stepGroups.updated(idx, nsg)))
      }.getOrElse(Left(NotFound(s"Could not find StepGroup $sgid")))

    /**
     * Adds a Step in a new StepGroup in the process at the defined position.
     * The position is calculated from a flattened view of the steps in all
     * the step groups. If the index is larger than the current number of steps,
     * the Step is appended to the Process. If not it will be added at the given
     * index, shifting tailing steps to the right.
     *
     * @param proc the Process
     * @param step the Step to insert
     * @param pos the position to insert the Step in the list of steps
     * @return a Process with the new Step added to the list of steps
     */
    private[hipe] def insertStep(proc: Process, step: Step, pos: Int): Process =
      if (pos > proc.stepGroups.length) appendStep(proc, step)
      else proc.copy(stepGroups = proc.stepGroups.insert(StepGroup.create(step), pos))

    /**
     * Adds a Step to the specified StepGroup at the defined position. The position is
     * calculated based on size of the StepGroup. If the pos is larger than the number
     * steps in the group, the Step is appended to the Process. If not it will be added
     * at the given index, shifting tailing steps to the right.
     *
     * @param proc the Process
     * @param sgid the StepGroup to add a step to
     * @param step the Step to insert
     * @param pos the position in the StepGroup to insert the step
     * @return HIPEResult with the updated Process config
     */
    private[hipe] def insertStepToGroup(proc: Process, sgid: StepGroupId, step: Step, pos: Int): HIPEResult[Process] =
      proc.stepGroups.findWithPosition(sgid).map {
        case (sg: StepGroup, idx: Int) =>
          if (pos > sg.steps.length) {
            appendStepToGroup(proc, sgid, step)
          } else {
            val nsg = sg.copy(steps = sg.steps.insert(step, pos))
            Right(proc.copy(stepGroups = proc.stepGroups.updated(idx, nsg)))
          }
      }.getOrElse(Left(NotFound(s"Could not find StepGroup $sgid")))

    /**
     * Allows for re-arranging groups of steps in the process...
     *
     * currPos > newPos: the StepGroup is moved `before` its current location
     * currPos < newPos: the StepGroup is moved `after` its current location
     * currPos == newPos: nothing is changed
     *
     * @param proc the Process
     * @param sgid the StepGroup to move
     * @param newPos the new index position to place the StepGroup
     * @return HIPEResult with the updated process config
     */
    private[hipe] def moveStepGroup(proc: Process, sgid: StepGroupId, newPos: Int): HIPEResult[Process] =
      proc.stepGroups.findWithPosition(sgid).map {
        case (group: StepGroup, currPos: Int) =>
          if (currPos == newPos) Left(NotPossible(s"Old ($currPos) and new ($newPos) positions are the same..."))
          else Right(proc.copy(stepGroups = proc.stepGroups.move(currPos, newPos)))
      }.getOrElse(Left(NotFound(s"Could not find StepGroup $sgid")))

    /**
     * Allows for re-arranging steps inside their enclosing StepGroup.
     *
     * currPos > newPos: the Step is moved `before` its current location
     * currPos < newPos: the Step is moved `after` its current location
     * currPos == newPos: nothing is changed
     *
     * @param proc the Process
     * @param sgid the StepGroupId containing the step to move
     * @param sid the StepId to move
     * @param newPos the new position of the Step relative to the enclosing StepGroup
     * @return HIPEResult with the updated process config
     */
    private[hipe] def moveStepInGroup(proc: Process, sgid: StepGroupId, sid: StepId, newPos: Int): HIPEResult[Process] =
      proc.stepGroups.findWithPosition(sgid).map {
        case (group: StepGroup, grpPos: Int) => group.steps.findWithPosition(sid).map {
          case (step: Step, currPos: Int) =>
            if (currPos == newPos) {
              Left(NotPossible(s"Old ($currPos) and new ($newPos) positions are the same..."))
            } else {
              val nsg = group.copy(steps = group.steps.move(currPos, newPos))
              Right(proc.copy(stepGroups = proc.stepGroups.updated(grpPos, nsg)))
            }
        }.getOrElse(Left(NotFound(s"Could not find Step: $sid")))
      }.getOrElse(Left(NotFound(s"Could not find StepGroup: $sgid")))

    /**
     * Moves a Step out of its current StepGroup into the given position in the target StepGroup.
     *
     * @param proc the Process
     * @param stepId the Id of the step to move
     * @param toSgid the StepGroup to move to
     * @param posInGrp the position in the target StepGroup to move the step to
     * @return HIPEResult with the updated Process config
     */
    private[hipe] def moveStepToGroup(proc: Process, stepId: StepId, toSgid: StepGroupId, posInGrp: Int): HIPEResult[Process] =
      proc.stepGroups.findStep(stepId).map {
        case (group: StepGroup, step: Step) =>
          val p1 = proc.removeStep(group, step)
          insertStepToGroup(p1, toSgid, step, posInGrp)
      }.getOrElse(Left(NotFound(s"Could not find step: $stepId")))

    /**
     * Will move a Step away from the enclosing StepGroup, and into a brand new StepGroup
     * at the given position in the process. If the original StepGroup is empty after the
     * move it will be removed from the process.
     *
     * @param proc the Process
     * @param stepId the StepIdId to move
     * @param newPos the position in the process where the new StepGroup will be added.
     * @return HIPEResult with the updated Process config
     */
    private[hipe] def moveStepToNewGroup(proc: Process, stepId: StepId, newPos: Int): HIPEResult[Process] =
      proc.stepGroups.findStep(stepId).map {
        case (group: StepGroup, step: Step) =>
          val p1 = proc.removeStep(group, step)
          Right(insertStep(p1, step, newPos - 1))
      }.getOrElse(Left(NotFound(s"Could not find step: $stepId")))

    /**
     * ¡¡¡ WARNING !!!
     *
     * Removes the Step with the given stepId if it exists. If the Step is the single entry in the
     * enclosing StepGroup, the StepGroup is also removed.
     *
     * @param proc the Process to remove a step from
     * @param stepId the stepId to remove
     * @return HIPEResult with the updated Process config
     */
    private[hipe] def removeStep(proc: Process, stepId: StepId): HIPEResult[Process] =
      proc.stepGroups.findStep(stepId).map {
        case (group: StepGroup, step: Step) => Right(proc.removeStep(group, step))
      }.getOrElse(Left(NotFound(s"Could not find step: $stepId")))

    /**
     * ¡¡¡ WARNING !!!
     *
     * Removes an entire StepGroup including the contained Step items.
     *
     * @param proc the Process
     * @param sgid the Id of the StepGroup to remove
     * @return
     */
    private[hipe] def removeGroup(proc: Process, sgid: StepGroupId): HIPEResult[Process] =
      proc.stepGroups.findWithPosition(sgid).map {
        case (group: StepGroup, pos: Int) => Right(proc.copy(stepGroups = proc.stepGroups.remove(pos)))
      }.getOrElse(Left(NotFound(s"Could not find group: $sgid")))
  }

  /**
   * Functions that perform computations on Task data
   */
  trait TaskOperations {

    private val logger = LoggerFactory.getLogger(classOf[TaskOperations])

    implicit def flattenOptRes(mr: Option[HIPEResult[Task]]): HIPEResult[Task] =
      mr.getOrElse(Left(NotPossible("Result does not contain data and cannot be flattened.")))

    implicit def resAsOpt(mr: HIPEResult[Task]): Option[Task] =
      mr.fold(
        err => None,
        task => Some(task)
      )

    /**
     * This function allows for moving a Task through the Process. If in a strict
     * Process, the movement will be restricted to the previous and/or next step. If
     * the overrideStrict flag is set to true, the default strict rules will be ignored,
     * and the move will be executed as if the process was open.
     *
     * If it is open, the task can be moved anywhere. Even for open processes, if
     * the minCompleted assignments criteria is configured but not met, the move
     * will not be allowed.
     *
     * @param p the Process to move the task within
     * @param t the Task to move
     * @param curr the current Step
     * @param next the target Step
     * @param overrideStrict set to true if you want to override default rules for strict processes
     *
     * @return A HIPEResult of Task. Will have a Left value if the move was restricted.
     */
    private[this] def mv(p: Process, t: Task, curr: Step, next: Step, overrideStrict: Boolean = false): HIPEResult[Task] = {
      if (t.isTaskCompleted(curr)) {
        if (p.strict && !overrideStrict) {
          val target = next.id.get
          p.prevNextSteps(t.stepId) match {
            case PrevOrNext(prv, nxt) if prv.id.contains(target) || nxt.id.contains(target) => Right(t.initAssignmentsFor(next))
            case PrevOnly(prv) if prv.id.contains(target) => Right(t.initAssignmentsFor(next))
            case NextOnly(nxt) if nxt.id.contains(target) => Right(t.initAssignmentsFor(next))
            case _ => Left(NotAllowed(s"Moving to step $target not possible...ignoring"))
          }
        }
        else {
          Right(t.initAssignmentsFor(next))
        }
      } else {
        Left(Incomplete(s"Requires ${curr.minCompleted} assignments to be completed"))
      }
    }

    /**
     * This function will move a Task to the current Steps immediate (right) Next step.
     *
     * @param p the Process
     * @param t the Task to move
     * @param curr the current Step
     * @return A HIPEResult of Task. Will have a Left value if the move was restricted.
     */
    private[hipe] def toNext(p: Process, t: Task, curr: Step): HIPEResult[Task] =
      p.stepGroups.nextStepFrom(t.stepId).map(s => mv(p, t, curr, s)).getOrElse {
        Left(NotFound(s"Could not find next step for ${t.stepId}"))
      }

    /**
     * This function will move a Task to the current Steps immediate (left) previous step.
     *
     * @param p the Process
     * @param t the Task to move
     * @param curr the current Step
     * @return A HIPEResult of Task. Will have a Left value if the move was restricted.
     */
    private[hipe] def toPrev(p: Process, t: Task, curr: Step): HIPEResult[Task] =
      p.stepGroups.previousStepFrom(t.stepId).map(s => mv(p, t, curr, s)).getOrElse {
        Left(NotFound(s"Could not find previous step for ${t.stepId}"))
      }

    /**
     * Performs a {{{mv}}} operation on the task by first validating the transition rules defined
     * on the current Step. If an appropriate rule is found, the corresponding move will be executed.
     *
     * In the case where no rules are found, the move operation defined in the fallback function will
     * be executed instead.
     *
     * @param proc the Process
     * @param task the Task to move
     * @param fallback function defining an alternative move function if no transition rules are defined.
     *
     * @return A HIPEResult of Task. Will have a Left value if the move was not possible.
     */
    private[hipe] def transition(proc: Process, task: Task)(fallback: (Step) => HIPEResult[Task]) = {
      val curr = proc.step(task.stepId).get
      curr.transitionRules.flatMap(_.find(_.taskState == task.state).map(_.transitionRule.exec)).map {
        case Right(res) => res.sd match {
          case n: Next => toNext(proc, task, curr)
          case p: Prev => toPrev(proc, task, curr)
          case Goto(s) =>
            proc.step(s).map(n => mv(proc, task, curr, n, overrideStrict = true)).getOrElse {
              Left(NotFound(s"Could not find goto step $s for ${task.id}"))
            }
        }
        case Left(err) =>
          logger.error( s"""${err.msg} - in "${err.source}" at position ${err.pos}""")
          Left(BadArgument(err.msg))
      }.getOrElse(fallback(curr))
    }

    /**
     * Will calculate the next step in the process and move the Task accordingly.
     *
     * @param p the Process
     * @param t the Task to move
     * @return HIPEResult with the updated Task
     */
    private[hipe] def moveToNext(p: Process, t: Task): HIPEResult[Task] = transition(p, t)(curr => toNext(p, t, curr))

    /**
     * Will calculate the previous step in the process and move the Task accordingly.
     *
     * @param p the Process
     * @param t the Task to move
     * @return HIPEResult with the updated Task
     */
    private[hipe] def moveToPrevious(p: Process, t: Task): HIPEResult[Task] = transition(p, t)(prev => toPrev(p, t, prev))

    /**
     * This function allows for moving a Task through the Process. If in a strict
     * Process, the movement will be restricted to the previous and/or next step.
     * If it is open, the task can be moved anywhere. Even for open processes, if
     * the minCompleted assignments criteria is configured but not met, the move
     * will not be allowed.
     *
     * @param proc the Process to move the task within
     * @param newStepId The new StepId to move to
     * @return A HIPEResult of Task. Will have a Left value if the move was restricted.
     */
    private[hipe] def moveTask(proc: Process, task: Task, newStepId: StepId): HIPEResult[Task] =
      proc.step(newStepId).map(next => mv(proc, task, proc.step(task.stepId).get, next))

    /**
     * Will try to creates a new Task and "place" it in the first step of the process.
     * And initialise the steps configured minimum number of assignments.
     *
     * @param by the UserId creating the task
     * @param proc the Process
     * @param taskTitle The title of the task
     * @param taskDesc optional description text
     * @return Option[Task]
     */
    private[hipe] def createTask(by: UserId, proc: Process, taskTitle: String, taskDesc: Option[String]): Option[Task] =
      for {
        step <- proc.stepGroups.headOption.flatMap(_.steps.headOption)
        pid <- proc.id
        sid <- step.id
      } yield {
        val version = VersionStamp(created = Some(UserStamp.create(by)))
        val t = Task(
          v = Some(version),
          id = TaskId.createOpt(),
          processId = pid,
          stepId = sid,
          title = taskTitle,
          description = taskDesc,
          state = TaskStates.Open()
        )
        t.initAssignmentsFor(step)
      }

    /**
     * Will try to creates a new Task and "place" it in the first step of the process.
     * And initialise the steps configured minimum number of assignments.
     *
     * @param by the UserId creating the task
     * @param proc the Process
     * @param task the Task to base initialisation on
     * @return Option[Task]
     */
    private[hipe] def createTask(by: UserId, proc: Process, task: Task): Option[Task] =
      for {
        step <- proc.stepGroups.headOption.flatMap(_.steps.headOption)
        pid <- proc.id
        sid <- step.id
      } yield {
        val version = VersionStamp(created = Some(UserStamp.create(by)))
        val t = task.copy(
          v = Some(version),
          id = TaskId.createOpt(),
          processId = pid,
          stepId = sid,
          state = TaskStates.Open()
        )
        t.initAssignmentsFor(step)
      }

    /**
     * Will attempt to assign the given userId to an assignment on the provided Task.
     *
     * @param task the Task give an assignment on
     * @param assignTo the userId to assign
     * @return Some Task if an assignment was given to the user, otherwise None.
     */
    private[hipe] def assign(task: Task, assignTo: UserId): Option[Task] =
      task.assignmentApply(
        cond = t => !t.assignments.exists(_.assignee.contains(assignTo)),
        cp = _.filterNot(_.completed).find(_.assignee.isEmpty).map { a =>
          val aa: Assignment = a.copy(assignee = Some(assignTo))
          aa.assignmentStateApply(AssignmentStates.Assigned())
        }
      )

    /**
     * Attempts to mark a users assignment as completed.
     *
     * @param assignee the userId that potentially has an asignment
     * @param task the Task where the assignment is located
     * @return the updated Task or None
     */
    private[hipe] def completeAssignment(assignee: UserId, task: Task): Option[Task] =
      task.assignmentApply(
        cond = _.assignments.exists(_.assignee.contains(assignee)),
        cp = _.filterNot(_.completed).find(_.assignee.contains(assignee)).map { a =>
          a.assignmentStateApply(AssignmentStates.Completed())
        }
      )

    /**
     * Force completes all Assignments in the Task
     *
     * @param task the Task
     * @return the updated Task
     */
    private[hipe] def completeAll(task: Task): Task = {
      val assignments = task.assignments.map(_.assignmentStateApply(AssignmentStates.Completed()))
      task.copy(assignments = assignments)
    }

    /**
     * Convenience function for moving the Task to the next step. Marks the
     * task state as "Approved"
     *
     * @param proc the Process
     * @param task the Task to approve
     * @return HIPEResult with the new Task
     */
    private[hipe] def approve(proc: Process, task: Task): HIPEResult[Task] = {
      moveToNext(proc, task.copy(state = TaskStates.Approved()))
    }

    /**
     * Convenience function for moving the Task to the previous step. Marks the
     * task state as "Rejected"
     *
     * @param proc the Process
     * @param task the Task to reject
     * @return HIPEResult with the new Task
     */
    private[hipe] def reject(proc: Process, task: Task): HIPEResult[Task] = {
      val assigns = task.assignments.map(_.assignmentStateApply(AssignmentStates.Aborted()))
      moveToPrevious(proc, task.copy(state = TaskStates.Rejected(), assignments = assigns))
    }

    // TODO: Implement me
    private[hipe] def consolidate(proc: Process, task: Task): HIPEResult[Task] = {
      // call complete all tasks
      ???
    }

  }

}