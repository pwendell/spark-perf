package streaming.perf

import org.apache.spark.util.Distribution
import org.apache.spark.rdd.RDD
import org.apache.spark.streaming.{Milliseconds, StreamingContext, DStream, Time}
import org.apache.spark.streaming.scheduler.StatsReportListener
import StreamingContext._

import streaming.perf.util._
import org.apache.spark.storage.StorageLevel

abstract class KVDataTest extends PerfTest {

  import KVDataTest._

  val NUM_STREAMS =      ("num-streams", "number of input streams")
  val RECORDS_PER_SEC =  ("records-per-sec", "number of records generated per second")
  val REDUCE_TASKS =     ("reduce-tasks",  "number of reduce tasks")
  val UNIQUE_KEYS =      ("unique-keys",   "(approx) number of unique keys")
  val UNIQUE_VALUES =    ("unique-values", "(approx) number of unique values per key")
  val MEMORY_SERIALIZATION = ("memory-serialization", "whether memory-persisted data is serialized")
  //val KEY_LENGTH =       ("key-length",    "length of keys in characters")
  //val VALUE_LENGTH =     ("value-length",  "length of values in characters")

  var numStreams: Int = _
  var recordsPerSec: Long = _
  var reduceTasks: Int = _
  var uniqueKeys: Long = _
  var uniqueValues: Long = _
  var storageLevel: StorageLevel = _

  override def longOptions = super.longOptions ++
    Seq(NUM_STREAMS, RECORDS_PER_SEC, REDUCE_TASKS, UNIQUE_KEYS, UNIQUE_VALUES)

  override def stringOptions = super.stringOptions

  override def booleanOptions = super.booleanOptions ++ Seq(MEMORY_SERIALIZATION)

  def run(): String = {
    numStreams = longOptionValue(NUM_STREAMS).toInt
    recordsPerSec = longOptionValue(RECORDS_PER_SEC)
    reduceTasks = longOptionValue(REDUCE_TASKS).toInt
    uniqueKeys = longOptionValue(UNIQUE_KEYS)
    uniqueValues = longOptionValue(UNIQUE_VALUES)
    storageLevel = booleanOptionValue(MEMORY_SERIALIZATION) match {
      case true => StorageLevel.MEMORY_ONLY_SER
      case false => StorageLevel.MEMORY_ONLY
    }

    val numBatches = (totalDurationSec * 1000 / batchDurationMs).toInt
    assert(
      numBatches > IGNORED_BATCHES,
      "# batches (" + numBatches + ") to run not more than # ignored batches (" + IGNORED_BATCHES + "). " +
        "Increase total-duration config."
    )
    
    // setup listener
    @transient val statsReportListener = new StatsReportListener(numBatches)
    ssc.addStreamingListener(statsReportListener)
    
    // setup streams
    val unifiedInputStream = setupInputStreams(numStreams)
    val outputStream = setupOutputStream(unifiedInputStream)
    outputStream.count.register
    outputStream.foreach((rdd: RDD[_], time: Time) => {
      // @transient val reporter = statsReportListener
      // if (time.milliseconds % 2000 == 0) println("Stats at " + time + ": " + processResults(reporter))
    })

    // run test
    ssc.start()
    val startTime = System.currentTimeMillis
    Thread.sleep(totalDurationSec * 1000)
    ssc.stop()
    processResults(statsReportListener)
  }

  // Setup multiple input streams and union them
  def setupInputStreams(numStreams: Int): DStream[(String, String)] = {
    val dataGenerators = (1 to numStreams).map(streamIndex =>
      new DataGenerator(sc, batchDurationMs, recordsPerSec, uniqueKeys, uniqueValues, streamIndex))
    val inputStreams = dataGenerators.map(dg =>
      new CustomInputDStream[(String, String)](ssc, dg.generateRDD(_)))
    ssc.union(inputStreams)
  }

  // Setup the streaming computations
  def setupOutputStream(inputStream: DStream[(String, String)]): DStream[_]
}

object KVDataTest {
  val IGNORED_BATCHES = 10
 
  // Generate statistics from the processing data
  def processResults(statsReportListener: StatsReportListener): String = {
    val processingDelays = statsReportListener.batchInfos.flatMap(_.processingDelay).map(_.toDouble / 1000.0)
    val distrib = new Distribution(processingDelays.takeRight(processingDelays.size - IGNORED_BATCHES))
    val statCounter = distrib.statCounter
    val quantiles = Array(0,0.25,0.5,0.75,1.0)
    val quantileValues = quantiles.zip(distrib.getQuantiles(quantiles)).toMap
    val resultString = "count: %d, avg: %.3f s, stdev: %.3f s, min: %.3f s, 25%%: %.3f s, 50%%: %.3f s, 75%%: %.3f s, max: %.3f s".format(
      processingDelays.size, statCounter.mean, statCounter.stdev, quantileValues(0), quantileValues(0.25), quantileValues(0.5),
      quantileValues(0.75), quantileValues(1.0)
    )
    resultString
  }
}

abstract class WindowKVDataTest extends KVDataTest {
  val WINDOW_DURATION = ("window-duration", "Duration of the window")

  var windowDurationMs: Long = _

  override def longOptions = super.longOptions ++ Seq(WINDOW_DURATION)

  override def run(): String = {
    windowDurationMs = longOptionValue(WINDOW_DURATION)
    super.run()
  }
}

class StateByKeyTest extends KVDataTest {
  // Setup the streaming computations
  def setupOutputStream(inputStream: DStream[(String, String)]): DStream[_] = {
    val updateFunc = (values: Seq[Long], state: Option[Long]) => {
      Some(values.foldLeft(0L)(_ + _) + state.getOrElse(0L))
    }
    inputStream.map(x => (x._1, x._2.toLong)).updateStateByKey[Long](updateFunc, reduceTasks).persist(storageLevel)
  }
}

class ReduceByKeyAndWindowTest extends WindowKVDataTest {
  // Setup the streaming computations
  def setupOutputStream(inputStream: DStream[(String, String)]): DStream[_] = {
    inputStream.reduceByKeyAndWindow((x: String, y: String) => x + y,
      Milliseconds(windowDurationMs), Milliseconds(batchDurationMs), reduceTasks)
  }
}

class GroupByKeyAndWindowTest extends WindowKVDataTest {
  // Setup the streaming computations
  def setupOutputStream(inputStream: DStream[(String, String)]): DStream[_] = {
    inputStream.groupByKeyAndWindow(Milliseconds(windowDurationMs), Milliseconds(batchDurationMs), reduceTasks)
  }
}
