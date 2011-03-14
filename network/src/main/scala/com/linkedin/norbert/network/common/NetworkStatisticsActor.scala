package com.linkedin.norbert
package network
package common

import logging.Logging
import jmx.{RequestTimeTracker}
import norbertutils._
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}
import jmx.JMX.MBean
import cluster.Node
import java.util.{UUID, Map => JMap}
import netty.HealthScoreCalculator

object CachedNetworkStatistics {
  def apply[GroupIdType, RequestIdType](clock: Clock, timeWindow: Long, refreshInterval: Long): CachedNetworkStatistics[GroupIdType, RequestIdType] = {
    new CachedNetworkStatistics(NetworkStatisticsTracker(clock, timeWindow), clock, refreshInterval)
  }
}

case class CacheMaintainer[T](clock: Clock, ttl: Long, fn: () => T) {
  val refreshing = new AtomicBoolean(false)
  val lastUpdateTime = new AtomicLong(0)
  @volatile var item: T = _

  private def refresh {
    val lut = lastUpdateTime.get
    val now = clock.getCurrentTime

    if(item == null || now - lut > ttl) {
      // Let one thread pass through to update the calculation
      if(refreshing.compareAndSet(false, true)) {
        lastUpdateTime.set(now)

        refresh0

        refreshing.set(false)
      }
    }
  }

  private def refresh0 {
    item = fn()
  }

  def get: T = {
    refresh
    item
  }
}

class CachedNetworkStatistics[GroupIdType, RequestIdType](private val stats: NetworkStatisticsTracker[GroupIdType, RequestIdType], clock: Clock, refreshInterval: Long) {
  val finishedArray = CacheMaintainer(clock, refreshInterval, () => stats.getFinishedArray)
  val timings = CacheMaintainer(clock, refreshInterval, () => stats.getTimings)
  val pendingTimings = CacheMaintainer(clock, refreshInterval, () => stats.getPendingTimings)

  def beginRequest(groupId: GroupIdType, requestId: RequestIdType) {
    stats.beginRequest(groupId, requestId)
  }

  def endRequest(groupId: GroupIdType, requestId: RequestIdType) {
    stats.endRequest(groupId, requestId)
  }

  def reset { stats.reset }

  private def calculate(map: Map[GroupIdType, Array[Int]], p: Double) = {
    Statistics(map.mapValues { v =>
      StatsEntry(calculatePercentile(v, p), v.length, v.sum)
    })
  }

  val statisticsCache =
    new java.util.concurrent.ConcurrentHashMap[Double, CacheMaintainer[JoinedStatistics[GroupIdType]]]

  def getStatistics(p: Double) = {
    atomicCreateIfAbsent(statisticsCache, p) { k =>
      CacheMaintainer(clock, refreshInterval, () => {
        JoinedStatistics(
          calculate(timings.get, p),
          calculate(pendingTimings.get, p),
          () => finishedArray.get.mapValues(rps(_)))
      })
    }.get
  }

  private def rps(data: Array[(Long, Int)]): Int = {
    val now = clock.getCurrentTime

    implicit val timeOrdering: Ordering[(Long, Int)] = new Ordering[(Long, Int)] {
      def compare(x: (Long, Int), y: (Long, Int)) = (x._1 - y._1).asInstanceOf[Int]
    }

    val bs = binarySearch(data, (now - 1000L, 0))
    val idx = if(bs < 0) -bs - 1 else bs
    data.size - idx
  }
}

case class StatsEntry(percentile: Double, size: Int, total: Int)
case class Statistics[K](map: Map[K, StatsEntry])
case class JoinedStatistics[K](finished: Statistics[K], pending: Statistics[K], rps: () => Map[K, Int])

private case class NetworkStatisticsTracker[GroupIdType, RequestIdType](clock: Clock, timeWindow: Long) extends Logging {
  private var timeTrackers: java.util.concurrent.ConcurrentMap[GroupIdType, RequestTimeTracker[RequestIdType]] =
    new java.util.concurrent.ConcurrentHashMap[GroupIdType, RequestTimeTracker[RequestIdType]]

  private def getTracker(groupId: GroupIdType) = {
    atomicCreateIfAbsent(timeTrackers, groupId) { k => new RequestTimeTracker(clock, timeWindow) }
  }

  def beginRequest(groupId: GroupIdType, requestId: RequestIdType) {
    getTracker(groupId).beginRequest(requestId)
  }

  def endRequest(groupId: GroupIdType, requestId: RequestIdType) {
    getTracker(groupId).endRequest(requestId)
  }

  import scala.collection.JavaConversions._

  def reset { timeTrackers.values.foreach(_.reset) }

  def getPendingTimings = {
    timeTrackers.toMap.mapValues( _.pendingRequestTimeTracker.getTimings)
  }

  def getTimings = {
    getFinishedArray.mapValues(array => array.map(_._2))
  }

  def getFinishedArray = {
    timeTrackers.toMap.mapValues( _.finishedRequestTimeTracker.getArray)
  }
}

trait NetworkClientStatisticsMBean {
  def getNumPendingRequests: JMap[Int, Int]

  def getMedianTimes: JMap[Int, Double]
  def get75thTimes: JMap[Int, Double]
  def get90thTimes: JMap[Int, Double]
  def get95thTimes: JMap[Int, Double]
  def get99thTimes: JMap[Int, Double]
  def getHealthScoreTimings: JMap[Int, Double]

  def getRPS: JMap[Int, Int]

  def getClusterRPS: Int
  def getClusterAverageTime: Double
  def getClusterPendingTime: Double

  def getClusterMedianTime: Double
  def getCluster75thTimes: Double
  def getCluster90th: Double
  def getCluster95th: Double
  def getCluster99th: Double
  def getClusterHealthScoreTiming: Double

  def reset

  // Jill will be very upset if I break her graphs
  def getRequestsPerSecond = getClusterRPS
  def getAverageRequestProcessingTime = getClusterAverageTime
}

class NetworkClientStatisticsMBeanImpl(serviceName: String, val stats: CachedNetworkStatistics[Node, UUID])
  extends MBean(classOf[NetworkClientStatisticsMBean], "service=%s".format(serviceName)) with HealthScoreCalculator
  with NetworkClientStatisticsMBean {

  private def getPendingStats(p: Double) = stats.getStatistics(p).pending
  private def getFinishedStats(p: Double) = stats.getStatistics(p).finished

  def getNumPendingRequests = toJMap(getPendingStats(0.5).map.map(kv => (kv._1.id, kv._2.size)))

  def getMedianTimes =
    toJMap(getFinishedStats(0.5).map.map(kv => (kv._1.id, kv._2.percentile)))

  def get75thTimes =
    toJMap(getFinishedStats(0.75).map.map(kv => (kv._1.id, kv._2.percentile)))

  def get90thTimes =
    toJMap(getFinishedStats(0.90).map.map(kv => (kv._1.id, kv._2.percentile)))

  def get95thTimes =
    toJMap(getFinishedStats(0.95).map.map(kv => (kv._1.id, kv._2.percentile)))

  def get99thTimes =
    toJMap(getFinishedStats(0.99).map.map(kv => (kv._1.id, kv._2.percentile)))

  def getHealthScoreTimings = {
    val s = stats.getStatistics(0.5)
    val f = s.finished
    val p = s.pending

    toJMap(f.map.map { case (n, nodeN) =>
      val nodeP = p.map.get(n).getOrElse(StatsEntry(0.0, 0, 0))
      (n.id, doCalculation(Statistics(Map(0 -> nodeP)),Statistics(Map(0 -> nodeN))))
    })
  }

  def getRPS = toJMap(stats.getStatistics(0.5).rps().map(kv => (kv._1.id, kv._2)))
//
//  def ave[K, V : Numeric](map: JMap[K, V]) = {
//    import scala.collection.JavaConversions._
//    average(map.values.sum, map.size)
//  }
//
  def getClusterAverageTime = {
    val s = stats.getStatistics(0.5).finished
    val total = s.map.values.map(_.total).sum
    val size = s.map.values.map(_.size).sum

    safeDivide(total, size)(0.0)
  }

  def getClusterPendingTime = {
    val s = stats.getStatistics(0.5).pending
    s.map.values.map(_.total).sum
  }

  def getClusterMedianTime = averagePercentiles(getFinishedStats(0.5))

  def getCluster75thTimes = averagePercentiles(getFinishedStats(0.75))

  def getCluster90th = averagePercentiles(getFinishedStats(0.90))

  def getCluster95th = averagePercentiles(getFinishedStats(0.95))

  def getCluster99th = averagePercentiles(getFinishedStats(0.99))

  def getClusterRPS = stats.getStatistics(0.5).rps().values.sum

  def getClusterHealthScoreTiming = doCalculation(getPendingStats(0.5), getFinishedStats(0.5))

  def reset = stats.reset
}

