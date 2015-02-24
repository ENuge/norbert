/*
 * Copyright 2009-2010 LinkedIn, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.linkedin.norbert.network.common

import com.linkedin.norbert.protos.NorbertExampleProtos
import com.linkedin.norbert.network.Serializer

trait SampleMessage {
  object Ping {
    implicit case object PingSerializer extends Serializer[Ping, Ping] {
      def requestName = "ping"
      def responseName = "pong"

      def requestToBytes(message: Ping) =
        NorbertExampleProtos.Ping.newBuilder.setTimestamp(message.timestamp).build.toByteArray

      def requestFromBytes(bytes: Array[Byte]) = {
        Ping(NorbertExampleProtos.Ping.newBuilder.mergeFrom(bytes).build.getTimestamp)
      }

      def responseToBytes(message: Ping) =
        requestToBytes(message)

      def responseFromBytes(bytes: Array[Byte]) =
        requestFromBytes(bytes)
    }
  }

  case class Ping(timestamp: Long = System.currentTimeMillis)
  val request = new Ping

  // Added by HMC clinic
  //a ping which has an increased priority for testing prioritization
  object PriorityPing extends Ping{
    implicit case object PriorityPingSerializer extends Serializer[PriorityPing, PriorityPing] {
      def requestName = "ping"
      def responseName = "pong"
      override def priority = 5

      def requestToBytes(message: PriorityPing) =
        NorbertExampleProtos.Ping.newBuilder.setTimestamp(message.timestamp).build.toByteArray

      def requestFromBytes(bytes: Array[Byte]) = {
         var timestamp = (NorbertExampleProtos.Ping.newBuilder.mergeFrom(bytes).build.getTimestamp)
        PriorityPing(timestamp)
      }

      def responseToBytes(message: PriorityPing) =
        requestToBytes(message)

      def responseFromBytes(bytes: Array[Byte]) =
        requestFromBytes(bytes)
    }
  }

  case class PriorityPing(timestamp: Long = System.currentTimeMillis)
  val priorityRequest = new PriorityPing
}
