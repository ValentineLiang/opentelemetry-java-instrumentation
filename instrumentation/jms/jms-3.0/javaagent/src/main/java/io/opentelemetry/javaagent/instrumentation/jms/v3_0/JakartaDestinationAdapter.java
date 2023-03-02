/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.jms.v3_0;

import io.opentelemetry.javaagent.instrumentation.jms.DestinationAdapter;
import jakarta.jms.Destination;
import jakarta.jms.JMSException;
import jakarta.jms.Queue;
import jakarta.jms.TemporaryQueue;
import jakarta.jms.TemporaryTopic;
import jakarta.jms.Topic;

public final class JakartaDestinationAdapter implements DestinationAdapter {

  public static DestinationAdapter create(Destination destination) {
    return new JakartaDestinationAdapter(destination);
  }

  private final Destination destination;

  private JakartaDestinationAdapter(Destination destination) {
    this.destination = destination;
  }

  @Override
  public boolean isQueue() {
    return destination instanceof Queue;
  }

  @Override
  public boolean isTopic() {
    return destination instanceof Topic;
  }

  @Override
  public String getQueueName() throws JMSException {
    if (!(destination instanceof Queue)) {
      throw new IllegalStateException(
          "This destination is not a Queue; make sure to call isQueue() before");
    }
    return ((Queue) destination).getQueueName();
  }

  @Override
  public String getTopicName() throws JMSException {
    if (!(destination instanceof Topic)) {
      throw new IllegalStateException(
          "This destination is not a Topic; make sure to call isTopic() before");
    }
    return ((Topic) destination).getTopicName();
  }

  @Override
  public boolean isTemporaryQueue() {
    return destination instanceof TemporaryQueue;
  }

  @Override
  public boolean isTemporaryTopic() {
    return destination instanceof TemporaryTopic;
  }
}
