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

package net.e6tech.elements.common.actor;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.Props;
import akka.actor.typed.SpawnProtocol;
import akka.actor.typed.javadsl.AskPattern;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import com.typesafe.config.Config;
import net.e6tech.elements.common.actor.typed.WorkEvents;
import net.e6tech.elements.common.util.SystemException;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;

public class Guardian extends CommonBehavior<SpawnProtocol> {

    private Behavior<SpawnProtocol> main;
    private akka.actor.typed.ActorRef<WorkEvents> workerPool;
    private long timeout = 5000L;

    public static Guardian setup(String name, Config config,  long timeout, Behavior<WorkEvents> pool) {
        Guardian guardian = new Guardian();
        guardian.timeout = timeout;
        guardian.main = Behaviors.setup(
                context -> {
                    guardian.setContext(context);
                    return SpawnProtocol.behavior();
                });

        akka.actor.typed.ActorSystem<SpawnProtocol> system = akka.actor.typed.ActorSystem.create(guardian.getMain(), name, config);

        try {
            CompletionStage<ActorRef<WorkEvents>> stage = AskPattern.ask(system, // cannot use guardian.getSystem() because context is not set yet
                    replyTo -> new SpawnProtocol.Spawn(pool, "WorkerPool", Props.empty(), replyTo),
                    java.time.Duration.ofSeconds(5), system.scheduler());
            stage.whenComplete((ref, throwable) -> {
                guardian.workerPool = ref;
            });
        } catch (Exception e) {
            throw new SystemException(e);
        }

        return guardian;
    }

    private Guardian() {
    }

    public Behavior<SpawnProtocol> getMain() {
        return main;
    }

    @Override
    public Receive createReceive() {
        return newReceiveBuilder().build();
    }

    public CompletionStage<Void> async(Runnable runnable) {
        return async(runnable, timeout);
    }

    public CompletionStage<Void> async(Runnable runnable, long timeout) {
        return AskPattern.ask(workerPool, ref -> new WorkEvents.RunnableTask(ref, runnable),
                java.time.Duration.ofMillis(timeout), getSystem().scheduler());
    }

    public <R> CompletionStage<R> async(Callable<R> callable) {
        return async(callable, timeout);
    }

    public <R> CompletionStage<R> async(Callable<R> callable, long timeout) {
        CompletionStage<R> stage = AskPattern.ask(workerPool, ref -> new WorkEvents.CallableTask(ref, callable),
                java.time.Duration.ofMillis(timeout), getSystem().scheduler());
        return stage;
    }

}