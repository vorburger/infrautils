/*
 * Copyright (c) 2017 Red Hat, Inc. and others. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.infrautils.metrics;

public interface Counter extends CloseableMetric {

    default void increment() {
        increment(1);
    }

    void increment(long howMany);

    default void decrement() {
        decrement(1);
    }

    void decrement(long howMany);

    /**
     * Gets the total number of events. Beware that this could have overflown.
     * This is typically used in unit tests of metrics, more than to expose the metrics in production
     * (because exposing metrics is really the role of the infrautils metrics implementation of this API).
     */
    long get();
}
