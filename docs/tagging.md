---
layout: page
title: Tagging events
permalink: /tagging
nav_order: 20
---

# Tagging events

To tag events you'll need to create an [Event Adapter](https://doc.akka.io/docs/akka/current/persistence.html#event-adapters) that will wrap the event in a [akka.persistence.journal.Tagged](https://doc.akka.io/api/akka/current/akka/persistence/journal/Tagged.html) class with the given tags. The `Tagged` class will instruct `akka-persistence-postgres` to tag the event with the given set of tags.

The persistence plugin will __not__ store the `Tagged` class in the journal. It will strip the `tags` and `payload` from the `Tagged` class, and use the class only as an instruction to tag the event with the given tags and store the `payload` in the `message` field of the journal table.

```scala
package com.swissborg.example

import akka.persistence.journal.{ Tagged, WriteEventAdapter }
import com.swissborg.example.Person.{ LastNameChanged, FirstNameChanged, PersonCreated }

class TaggingEventAdapter extends WriteEventAdapter {
  override def manifest(event: Any): String = ""

  def withTag(event: Any, tag: String) = Tagged(event, Set(tag))

  override def toJournal(event: Any): Any = event match {
    case _: PersonCreated =>
      withTag(event, "person-created")
    case _: FirstNameChanged =>
      withTag(event, "first-name-changed")
    case _: LastNameChanged =>
      withTag(event, "last-name-changed")
    case _ => event
  }
}
```

The `EventAdapter` must be registered by adding the following to the root of `application.conf` Please see the  [demo-akka-persistence-postgres](https://github.com/mkubala/demo-akka-persistence-postgres) project for more information.

```bash
postgres-journal {
  event-adapters {
    tagging = "com.swissborg.example.TaggingEventAdapter"
  }
  event-adapter-bindings {
    "com.swissborg.example.Person$PersonCreated" = tagging
    "com.swissborg.example.Person$FirstNameChanged" = tagging
    "com.swissborg.example.Person$LastNameChanged" = tagging
  }
}
```

You can retrieve a subset of all events by specifying offset, or use `0L` to retrieve all events with a given tag. The offset corresponds to an ordered sequence number for the specific tag. Note that the corresponding offset of each event is provided in the `EventEnvelope`, which makes it possible to resume the stream at a later point from a given offset.

In addition to the offset the `EventEnvelope` also provides `persistenceId` and `sequenceNr` for each event. The `sequenceNr` is the sequence number for the persistent actor with the `persistenceId` that persisted the event. The `persistenceId` + `sequenceNr` is an unique identifier for the event.

The returned event stream contains only events that correspond to the given tag, and is ordered by the creation time of the events. The same stream elements (in same order) are returned for multiple executions of the same query. Deleted events are not deleted from the tagged event stream.
