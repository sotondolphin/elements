/*
 * Copyright 2015-2019 Futeh Kao
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.e6tech.elements.network.cluster.catalyst;

import java.util.ArrayList;
import java.util.Collection;

public class Gatherer<E> {

    Collection<E> collection = new ArrayList<>();

    public Gatherer() {
    }

    public Gatherer(Collection<E> collection) {
        this.collection = collection;
    }

    public Gatherer<E> gather(Collection<E> collection) {
        this.collection.addAll(collection);
        return this;
    }

    public Collection<E> collection() {
        return collection;
    }
}