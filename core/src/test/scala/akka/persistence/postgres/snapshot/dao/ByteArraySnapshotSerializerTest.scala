package akka.persistence.postgres.snapshot.dao

import java.time.{ LocalDateTime, ZoneOffset }

import akka.persistence.SnapshotMetadata
import akka.persistence.postgres.SharedActorSystemTestSpec
import akka.persistence.postgres.snapshot.dao.SnapshotTables.SnapshotRow
import akka.serialization.Serializers
import io.circe.Json
import org.scalatest.TryValues

class ByteArraySnapshotSerializerTest extends SharedActorSystemTestSpec with TryValues {

  val serializer = new ByteArraySnapshotSerializer(serialization)
  val fakeSnapshot = "fake snapshot"
  val payloadSer = serialization.serializerFor(fakeSnapshot.getClass)
  val serId = payloadSer.identifier
  val serManifest = Serializers.manifestFor(payloadSer, fakeSnapshot)

  it should "serialize snapshot and its metadata" in {
    val timestamp = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC)
    val result = serializer.serialize(SnapshotMetadata("per-1", 42, timestamp), fakeSnapshot)
    val row = result.get
    row.persistenceId should equal("per-1")
    row.sequenceNumber shouldBe 42
    row.created shouldBe timestamp
    row.metadata should equal {
      Json.obj(
        // serialization manifest for String should be blank and omitted
        "sid" -> Json.fromInt(serId))
    }
  }

  {

    val serializedPayload = {
      val timestamp = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC)
      val row = serializer.serialize(SnapshotMetadata("per-1", 42, timestamp), fakeSnapshot).get
      row.snapshot
    }

    it should "deserialize snapshot and metadata" in {
      val timestamp = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC)
      val meta = Json.obj("sid" -> Json.fromInt(serId))
      val row = SnapshotRow("per-1", 42, timestamp, serializedPayload, meta)
      val (metadata, snapshot) = serializer.deserialize(row).get
      snapshot should equal(fakeSnapshot)
      metadata should equal(SnapshotMetadata("per-1", 42, timestamp))
    }

    it should "deserialize metadata with legacy long keys" in {
      val timestamp = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC)
      val meta = Json.obj("serId" -> Json.fromInt(serId), "serManifest" -> Json.fromString(""))
      val row = SnapshotRow("per-1", 42, timestamp, serializedPayload, meta)
      val (metadata, _) = serializer.deserialize(row).get
      metadata should equal(SnapshotMetadata("per-1", 42, timestamp))
    }

    it should "deserialize metadata with mixed legacy long & new short keys - short keys takes precedence" in {
      val timestamp = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC)
      val meta = Json.obj(
        "sid" -> Json.fromInt(serId),
        "serId" -> Json.fromInt(-1),
        "serManifest" -> Json.fromString("this will be ignored"))
      val row = SnapshotRow("per-1", 42, timestamp, serializedPayload, meta)
      val (metadata, _) = serializer.deserialize(row).get
      metadata should equal(SnapshotMetadata("per-1", 42, timestamp))
    }
  }

}
