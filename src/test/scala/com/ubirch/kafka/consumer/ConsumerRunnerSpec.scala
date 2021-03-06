package com.ubirch.kafka.consumer

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ CountDownLatch, Executors }

import com.ubirch.TestBase
import com.ubirch.kafka.util.Exceptions.{ CommitTimeoutException, NeedForPauseException }
import com.ubirch.kafka.util.{ NameGiver, PortGiver }
import net.manub.embeddedkafka.EmbeddedKafkaConfig
import org.apache.kafka.clients.consumer.{ ConsumerRecord, ConsumerRecords, OffsetResetStrategy }
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.TimeoutException
import org.apache.kafka.common.serialization.StringDeserializer

import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }
import scala.language.{ implicitConversions, postfixOps }

class ConsumerRunnerSpec extends TestBase {

  implicit def executionContext = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(5))

  def processResult(_consumerRecords: Vector[ConsumerRecord[String, String]]) = new ProcessResult[String, String] {
    override val id: UUID = UUID.randomUUID()
    override val consumerRecords: Vector[ConsumerRecord[String, String]] = _consumerRecords

  }

  "Consumer Runner" must {

    "fail if topic is not provided" in {

      val configs = Configs(
        bootstrapServers = "localhost:" + "9000",
        groupId = "My_Group_ID",
        autoOffsetReset = OffsetResetStrategy.EARLIEST
      )

      val consumer = new ConsumerRunner[String, String]("cr-1") {
        override def process(consumerRecords: Vector[ConsumerRecord[String, String]]): Future[ProcessResult[String, String]] = {
          Future.successful(processResult(consumerRecords))
        }

      }

      consumer.setKeyDeserializer(Some(new StringDeserializer()))
      consumer.setValueDeserializer(Some(new StringDeserializer()))
      consumer.setForceExit(false) // We disable the ForceExit so that the Test doesn't exit
      consumer.setProps(configs)
      consumer.startPolling()

      Thread.sleep(5000) // We wait here so the change is propagated

      assert(!consumer.getRunning)

    }

    "fail if no serializers have been set" in {

      val configs = Configs(
        bootstrapServers = "localhost:" + "9000",
        groupId = "My_Group_ID",
        autoOffsetReset = OffsetResetStrategy.EARLIEST
      )

      val consumer = new ConsumerRunner[String, String]("cr-2") {
        override def process(consumerRecords: Vector[ConsumerRecord[String, String]]): Future[ProcessResult[String, String]] = {
          Future.successful(processResult(consumerRecords))
        }

      }

      consumer.setForceExit(false) // We disable the ForceExit so that the Test doesn't exit
      consumer.setProps(configs)
      consumer.startPolling()

      Thread.sleep(10000) // We wait here so the change is propagated
      assert(!consumer.getRunning)

    }

    "fail if props are empty" in {

      val consumer = new ConsumerRunner[String, String]("cr-3") {

        override def process(consumerRecords: Vector[ConsumerRecord[String, String]]): Future[ProcessResult[String, String]] = {
          Future.successful(processResult(consumerRecords))
        }
      }

      consumer.setKeyDeserializer(Some(new StringDeserializer()))
      consumer.setValueDeserializer(Some(new StringDeserializer()))
      consumer.setForceExit(false) // We disable the ForceExit so that the Test doesn't exit
      consumer.setProps(Map.empty)
      consumer.startPolling()

      Thread.sleep(5000) // We wait here so the change is propagated

      assert(!consumer.getRunning)

    }

    "consumer 100 entities successfully" in {

      val maxEntities = 100
      val futureMessages = scala.collection.mutable.ListBuffer.empty[String]
      val counter = new CountDownLatch(maxEntities)

      implicit val kafkaConfig: EmbeddedKafkaConfig = EmbeddedKafkaConfig(kafkaPort = PortGiver.giveMeKafkaPort, zooKeeperPort = PortGiver.giveMeZookeeperPort)

      withRunningKafka {

        val topic = NameGiver.giveMeATopicName

        val messages = (1 to maxEntities).map(i => "Hello " + i).toList
        messages.foreach { m =>
          publishStringMessageToKafka(topic, m)
        }

        val configs = Configs(
          bootstrapServers = "localhost:" + kafkaConfig.kafkaPort,
          groupId = "My_Group_ID",
          autoOffsetReset = OffsetResetStrategy.EARLIEST
        )

        val consumer = new ConsumerRunner[String, String]("cr-4") {

          override def process(consumerRecords: Vector[ConsumerRecord[String, String]]): Future[ProcessResult[String, String]] = {
            consumerRecords.headOption.foreach(x => futureMessages += x.value())
            counter.countDown()
            Future.successful(processResult(consumerRecords))
          }
        }

        consumer.setKeyDeserializer(Some(new StringDeserializer()))
        consumer.setValueDeserializer(Some(new StringDeserializer()))
        consumer.setForceExit(false)
        consumer.setTopics(Set(topic))
        consumer.setProps(configs)
        consumer.startPolling()

        counter.await()

        assert(futureMessages.size == maxEntities)
        assert(messages == futureMessages.toList)

      }

    }

    "run an NeedForPauseException and pause and then unpause with multiple partitions" in {
      val maxEntities = 10
      val futureMessages = scala.collection.mutable.ListBuffer.empty[String]
      val counter = new CountDownLatch(maxEntities)

      implicit val kafkaConfig: EmbeddedKafkaConfig = EmbeddedKafkaConfig(kafkaPort = PortGiver.giveMeKafkaPort, zooKeeperPort = PortGiver.giveMeZookeeperPort)

      withRunningKafka {

        val topic = NameGiver.giveMeATopicName

        createCustomTopic(topic, partitions = 40)

        val messages = (1 to maxEntities).map(i => "Hello " + i).toList
        messages.foreach { m =>
          publishStringMessageToKafka(topic, m)
        }

        Thread.sleep(1000)

        val configs = Configs(
          bootstrapServers = "localhost:" + kafkaConfig.kafkaPort,
          groupId = "My_Group_ID",
          autoOffsetReset = OffsetResetStrategy.EARLIEST
        )

        val consumer = new ConsumerRunner[String, String]("cr-5") {

          override def process(consumerRecords: Vector[ConsumerRecord[String, String]]): Future[ProcessResult[String, String]] = {
            consumerRecords.foreach(x => futureMessages += x.value())
            counter.countDown()
            Future.failed(NeedForPauseException("Need to pause", "yeah", maybeDuration = Some(1 seconds)))
          }
        }

        consumer.setKeyDeserializer(Some(new StringDeserializer()))
        consumer.setValueDeserializer(Some(new StringDeserializer()))
        consumer.setTopics(Set(topic))
        consumer.setProps(configs)
        consumer.setForceExit(false)
        consumer.setConsumptionStrategy(All)
        consumer.startPolling()

        Thread.sleep(1000)

        counter.await()

        assert(futureMessages.distinct.size == maxEntities)
        assert(messages.sorted == futureMessages.distinct.toList.sorted)

      }
    }

    "run an NeedForPauseException and pause and then unpause with multiple partitions with success with two instances" in {
      val maxEntities = 10
      val futureMessages = scala.collection.mutable.ListBuffer.empty[String]
      val counterA = new CountDownLatch(1)
      val counterB = new CountDownLatch(1)
      val successCounterA = new AtomicInteger(0)
      val successCounterB = new AtomicInteger(0)

      implicit val kafkaConfig: EmbeddedKafkaConfig = EmbeddedKafkaConfig(kafkaPort = PortGiver.giveMeKafkaPort, zooKeeperPort = PortGiver.giveMeZookeeperPort)

      withRunningKafka {

        val topic = NameGiver.giveMeATopicName

        createCustomTopic(topic, partitions = 40)

        val messages = (1 to maxEntities).map(i => "Hello " + i).toList
        messages.foreach { m =>
          publishStringMessageToKafka(topic, m)
        }

        Thread.sleep(1000)

        val configs = Configs(
          bootstrapServers = "localhost:" + kafkaConfig.kafkaPort,
          groupId = "My_Group_ID",
          autoOffsetReset = OffsetResetStrategy.EARLIEST
        )

        val consumerA = new ConsumerRunner[String, String]("cr-A") {

          override def process(consumerRecords: Vector[ConsumerRecord[String, String]]): Future[ProcessResult[String, String]] = {
            if (successCounterA.getAndIncrement() == maxEntities / 2) {
              consumerRecords.foreach(x => futureMessages += x.value())
              counterA.countDown()
              Future.successful(processResult(consumerRecords))
            } else {
              Future.failed(NeedForPauseException("Need to pause", "yeah", maybeDuration = Some(1 seconds)))
            }
          }
        }

        consumerA.setKeyDeserializer(Some(new StringDeserializer()))
        consumerA.setValueDeserializer(Some(new StringDeserializer()))
        consumerA.setTopics(Set(topic))
        consumerA.setProps(configs)
        consumerA.setConsumptionStrategy(All)
        consumerA.startPolling()

        val consumerB = new ConsumerRunner[String, String]("cr-B") {
          override def process(consumerRecords: Vector[ConsumerRecord[String, String]]): Future[ProcessResult[String, String]] = {
            if (successCounterB.getAndIncrement() == maxEntities / 2) {
              consumerRecords.foreach(x => futureMessages += x.value())
              counterB.countDown()
              Future.successful(processResult(consumerRecords))
            } else {
              Future.failed(NeedForPauseException("Need to pause", "yeah", maybeDuration = Some(1 seconds)))
            }
          }
        }

        consumerB.setKeyDeserializer(Some(new StringDeserializer()))
        consumerB.setValueDeserializer(Some(new StringDeserializer()))
        consumerB.setTopics(Set(topic))
        consumerB.setProps(configs)
        consumerB.setConsumptionStrategy(All)
        consumerB.startPolling()

        Thread.sleep(1000)

        counterA.await()
        counterB.await()

        assert(futureMessages.size == maxEntities)
        assert(messages.sorted == futureMessages.toList.sorted)

      }
    }

    "run an NeedForPauseException and pause and then unpause with multiple partitions with success" in {
      val maxEntities = 10
      val futureMessages = scala.collection.mutable.ListBuffer.empty[String]
      val counter = new CountDownLatch(1)
      val successCounter = new AtomicInteger(0)

      implicit val kafkaConfig: EmbeddedKafkaConfig = EmbeddedKafkaConfig(kafkaPort = PortGiver.giveMeKafkaPort, zooKeeperPort = PortGiver.giveMeZookeeperPort)

      withRunningKafka {

        val topic = NameGiver.giveMeATopicName

        createCustomTopic(topic, partitions = 40)

        val messages = (1 to maxEntities).map(i => "Hello " + i).toList
        messages.foreach { m =>
          publishStringMessageToKafka(topic, m)
        }

        Thread.sleep(1000)

        val configs = Configs(
          bootstrapServers = "localhost:" + kafkaConfig.kafkaPort,
          groupId = "My_Group_ID",
          autoOffsetReset = OffsetResetStrategy.EARLIEST
        )

        val consumer = new ConsumerRunner[String, String]("cr-5") {

          override def process(consumerRecords: Vector[ConsumerRecord[String, String]]): Future[ProcessResult[String, String]] = {
            if (successCounter.getAndIncrement() == maxEntities / 2) {
              consumerRecords.foreach(x => futureMessages += x.value())
              counter.countDown()
              Future.successful(processResult(consumerRecords))
            } else {
              Future.failed(NeedForPauseException("Need to pause", "yeah", maybeDuration = Some(1 seconds)))
            }
          }
        }

        consumer.setKeyDeserializer(Some(new StringDeserializer()))
        consumer.setValueDeserializer(Some(new StringDeserializer()))
        consumer.setTopics(Set(topic))
        consumer.setProps(configs)
        consumer.setConsumptionStrategy(All)
        consumer.startPolling()

        Thread.sleep(1000)

        counter.await()

        assert(futureMessages.size == maxEntities)
        assert(messages.sorted == futureMessages.toList.sorted)

      }
    }

    "run an NeedForPauseException and pause and then unpause" in {
      val maxEntities = 1
      val futureMessages = scala.collection.mutable.ListBuffer.empty[String]
      val counter = new CountDownLatch(maxEntities)

      implicit val kafkaConfig: EmbeddedKafkaConfig = EmbeddedKafkaConfig(kafkaPort = PortGiver.giveMeKafkaPort, zooKeeperPort = PortGiver.giveMeZookeeperPort)

      withRunningKafka {

        val topic = NameGiver.giveMeATopicName

        val messages = (1 to maxEntities).map(i => "Hello " + i).toList
        messages.foreach { m =>
          publishStringMessageToKafka(topic, m)
        }

        val configs = Configs(
          bootstrapServers = "localhost:" + kafkaConfig.kafkaPort,
          groupId = "My_Group_ID",
          autoOffsetReset = OffsetResetStrategy.EARLIEST
        )

        val consumer = new ConsumerRunner[String, String]("cr-5") {

          override def process(consumerRecords: Vector[ConsumerRecord[String, String]]): Future[ProcessResult[String, String]] = {
            consumerRecords.headOption.foreach(x => futureMessages += x.value())
            counter.countDown()
            Future.failed(NeedForPauseException("Need to pause", "yeah"))
          }
        }

        consumer.setKeyDeserializer(Some(new StringDeserializer()))
        consumer.setValueDeserializer(Some(new StringDeserializer()))
        consumer.setTopics(Set(topic))
        consumer.setProps(configs)
        consumer.startPolling()

        counter.await()

        assert(futureMessages.size == maxEntities)
        assert(messages == futureMessages.toList)

        Thread.sleep(2000)

        assert(consumer.getPausedHistory.get() >= 1)

        Thread.sleep(2000)

        assert(consumer.getUnPausedHistory.get() >= 1)

      }
    }

    "run an NeedForPauseException and pause and then unpause when throttling" in {
      val maxEntities = 1
      val futureMessages = scala.collection.mutable.ListBuffer.empty[String]
      val counter = new CountDownLatch(maxEntities)

      implicit val kafkaConfig: EmbeddedKafkaConfig = EmbeddedKafkaConfig(kafkaPort = PortGiver.giveMeKafkaPort, zooKeeperPort = PortGiver.giveMeZookeeperPort)

      withRunningKafka {

        val topic = NameGiver.giveMeATopicName

        val messages = (1 to maxEntities).map(i => "Hello " + i).toList
        messages.foreach { m =>
          publishStringMessageToKafka(topic, m)
        }

        val configs = Configs(
          bootstrapServers = "localhost:" + kafkaConfig.kafkaPort,
          groupId = "My_Group_ID",
          autoOffsetReset = OffsetResetStrategy.EARLIEST
        )

        val consumer = new ConsumerRunner[String, String]("cr-6") {

          override def process(consumerRecords: Vector[ConsumerRecord[String, String]]): Future[ProcessResult[String, String]] = {
            consumerRecords.headOption.foreach(x => futureMessages += x.value())
            counter.countDown()
            Future.failed(NeedForPauseException("Need to pause", "yeah"))
          }
        }

        consumer.setKeyDeserializer(Some(new StringDeserializer()))
        consumer.setValueDeserializer(Some(new StringDeserializer()))
        consumer.setForceExit(false)
        consumer.setTopics(Set(topic))
        consumer.setProps(configs)
        consumer.setDelaySingleRecord(10 millis)
        consumer.setDelayRecords(1000 millis)

        consumer.startPolling()

        counter.await()

        assert(futureMessages.size == maxEntities)
        assert(messages == futureMessages.toList)

        Thread.sleep(2000)

        assert(consumer.getPausedHistory.get() >= 1)

        Thread.sleep(2000)

        assert(consumer.getUnPausedHistory.get() >= 1)

      }

    }

    "consume complete after error" in {
      val maxEntities = 10
      val futureMessages = scala.collection.mutable.ListBuffer.empty[String]

      implicit val kafkaConfig: EmbeddedKafkaConfig = EmbeddedKafkaConfig(kafkaPort = PortGiver.giveMeKafkaPort, zooKeeperPort = PortGiver.giveMeZookeeperPort)

      withRunningKafka {

        val topic = NameGiver.giveMeATopicName

        val messages = (1 to maxEntities).map(i => "Hello " + i).toList
        messages.foreach { m =>
          publishStringMessageToKafka(topic, m)
        }

        val configs = Configs(
          bootstrapServers = "localhost:" + kafkaConfig.kafkaPort,
          groupId = "My_Group_ID",
          autoOffsetReset = OffsetResetStrategy.EARLIEST
        )

        val doErrorOn: Int = {
          val start = 1
          val end = maxEntities
          val rnd = new scala.util.Random
          start + rnd.nextInt((end - start) + 1)
        }

        var alreadyFailed = false
        var current = 1

        val consumer = new ConsumerRunner[String, String]("cr-7") {

          override def process(consumerRecords: Vector[ConsumerRecord[String, String]]): Future[ProcessResult[String, String]] = {
            current = current + 1
            if (current == doErrorOn && !alreadyFailed) {
              futureMessages.clear()
              alreadyFailed = true
              Future.failed(NeedForPauseException("Need to pause", "yeah"))
            } else {
              consumerRecords.headOption.foreach(x => futureMessages += x.value())
              Future.successful(processResult(consumerRecords))
            }

          }
        }

        consumer.setKeyDeserializer(Some(new StringDeserializer()))
        consumer.setValueDeserializer(Some(new StringDeserializer()))
        consumer.setTopics(Set(topic))
        consumer.setForceExit(false)
        consumer.setProps(configs)
        consumer.startPolling()

        Thread.sleep(10000)

        assert(futureMessages.toSet == messages.toSet)
        assert(futureMessages.toSet.size == maxEntities)

      }
    }

    "try to commit after TimeoutException" in {

      val maxEntities = 1
      val attempts = new CountDownLatch(3)

      implicit val kafkaConfig: EmbeddedKafkaConfig = EmbeddedKafkaConfig(kafkaPort = PortGiver.giveMeKafkaPort, zooKeeperPort = PortGiver.giveMeZookeeperPort)

      withRunningKafka {

        val topic = NameGiver.giveMeATopicName

        val messages = (1 to maxEntities).map(i => "Hello " + i).toList
        messages.foreach { m =>
          publishStringMessageToKafka(topic, m)
        }

        val configs = Configs(
          bootstrapServers = "localhost:" + kafkaConfig.kafkaPort,
          groupId = "My_Group_ID",
          autoOffsetReset = OffsetResetStrategy.EARLIEST
        )

        val consumer: ConsumerRunner[String, String] = new ConsumerRunner[String, String]("cr-8") {

          override def process(consumerRecords: Vector[ConsumerRecord[String, String]]): Future[ProcessResult[String, String]] = {
            Future.successful(processResult(consumerRecords))
          }

          override def oneFactory(currentPartitionIndex: Int, currentPartition: TopicPartition, allPartitions: Set[TopicPartition], consumerRecords: ConsumerRecords[String, String]): ProcessRecordsOne = {
            new ProcessRecordsOne(currentPartitionIndex, currentPartition, allPartitions, consumerRecords) {
              override def commitFunc(): Vector[Unit] = {
                attempts.countDown()
                throw CommitTimeoutException("Commit timed out", () => commitFunc(), new TimeoutException("Timed out"))
              }
            }
          }
        }

        consumer.setKeyDeserializer(Some(new StringDeserializer()))
        consumer.setValueDeserializer(Some(new StringDeserializer()))
        consumer.setForceExit(false)
        consumer.setTopics(Set(topic))
        consumer.setProps(configs)
        consumer.startPolling()

        attempts.await()
        assert(attempts.getCount == 0)

      }

    }

    "try to commit after TimeoutException and another Exception" in {
      val maxEntities = 1
      val attempts = new CountDownLatch(4)

      implicit val kafkaConfig: EmbeddedKafkaConfig = EmbeddedKafkaConfig(kafkaPort = PortGiver.giveMeKafkaPort, zooKeeperPort = PortGiver.giveMeZookeeperPort)

      withRunningKafka {

        val topic = NameGiver.giveMeATopicName

        val messages = (1 to maxEntities).map(i => "Hello " + i).toList
        messages.foreach { m =>
          publishStringMessageToKafka(topic, m)
        }

        val configs = Configs(
          bootstrapServers = "localhost:" + kafkaConfig.kafkaPort,
          groupId = "My_Group_ID",
          autoOffsetReset = OffsetResetStrategy.EARLIEST
        )

        val consumer: ConsumerRunner[String, String] = new ConsumerRunner[String, String]("cr-9") {

          override def process(consumerRecords: Vector[ConsumerRecord[String, String]]): Future[ProcessResult[String, String]] = {
            Future.successful(processResult(consumerRecords))
          }

          override def oneFactory(currentPartitionIndex: Int, currentPartition: TopicPartition, allPartitions: Set[TopicPartition], consumerRecords: ConsumerRecords[String, String]): ProcessRecordsOne = {
            new ProcessRecordsOne(currentPartitionIndex, currentPartition, allPartitions, consumerRecords) {
              override def commitFunc(): Vector[Unit] = {
                attempts.countDown()
                if (attempts.getCount == 2) {
                  attempts.countDown()
                  attempts.countDown()
                  throw new Exception("Another exception")
                } else {
                  throw CommitTimeoutException("Commit timed out", () => commitFunc(), new TimeoutException("Timed out"))
                }
              }
            }
          }
        }

        consumer.setKeyDeserializer(Some(new StringDeserializer()))
        consumer.setValueDeserializer(Some(new StringDeserializer()))
        consumer.setForceExit(false)
        consumer.setTopics(Set(topic))
        consumer.setProps(configs)
        consumer.startPolling()

        attempts.await()
        assert(attempts.getCount == 0)

      }
    }

    "try to commit after TimeoutException and OK after" in {
      val maxEntities = 1

      val committed = new CountDownLatch(1)
      val failedProcesses = new CountDownLatch(3)
      var committedN = 0

      implicit val kafkaConfig: EmbeddedKafkaConfig = EmbeddedKafkaConfig(kafkaPort = PortGiver.giveMeKafkaPort, zooKeeperPort = PortGiver.giveMeZookeeperPort)

      withRunningKafka {

        val topic = NameGiver.giveMeATopicName

        val messages = (1 to maxEntities).map(i => "Hello " + i).toList
        messages.foreach { m =>
          publishStringMessageToKafka(topic, m)
        }

        val configs = Configs(
          bootstrapServers = "localhost:" + kafkaConfig.kafkaPort,
          groupId = "My_Group_ID",
          autoOffsetReset = OffsetResetStrategy.EARLIEST
        )

        val consumer: ConsumerRunner[String, String] = new ConsumerRunner[String, String]("cr-9") {

          override def process(consumerRecords: Vector[ConsumerRecord[String, String]]): Future[ProcessResult[String, String]] = {
            Future.successful(processResult(consumerRecords))
          }

          override def oneFactory(currentPartitionIndex: Int, currentPartition: TopicPartition, allPartitions: Set[TopicPartition], consumerRecords: ConsumerRecords[String, String]): ProcessRecordsOne = {
            new ProcessRecordsOne(currentPartitionIndex, currentPartition, allPartitions, consumerRecords) {
              override def commitFunc(): Vector[Unit] = {
                failedProcesses.countDown()
                if (failedProcesses.getCount == 1) {
                  val f = super.commitFunc()
                  failedProcesses.countDown()
                  committed.countDown()
                  f
                } else {
                  throw CommitTimeoutException("Commit timed out", () => commitFunc(), new TimeoutException("Timed out"))
                }
              }
            }
          }
        }

        consumer.setKeyDeserializer(Some(new StringDeserializer()))
        consumer.setValueDeserializer(Some(new StringDeserializer()))
        consumer.setTopics(Set(topic))
        consumer.setProps(configs)
        consumer.onPostCommit(i => committedN = i)
        consumer.startPolling()

        committed.await()
        failedProcesses.await()
        assert(committedN == 1)
        assert(committed.getCount == 0)
        assert(failedProcesses.getCount == 0)

      }
    }

  }

}
