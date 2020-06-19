/*
 * Copyright (C) 2014 - 2019 Dennis Vriend <https://github.com/dnvriend>
 * Copyright (C) 2019 - 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.postgres.journal.dao

private[postgres] sealed trait FlowControl

private[postgres] object FlowControl {

  /** Keep querying - used when we are sure that there is more events to fetch */
  case object Continue extends FlowControl

  /**
   * Keep querying with delay - used when we have consumed all events,
   * but want to poll for future events
   */
  case object ContinueDelayed extends FlowControl

  /** Stop querying - used when we reach the desired offset  */
  case object Stop extends FlowControl
}
