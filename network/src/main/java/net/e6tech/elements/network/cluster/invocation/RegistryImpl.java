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

package net.e6tech.elements.network.cluster.invocation;

import akka.actor.typed.ActorRef;
import net.e6tech.elements.common.actor.typed.Guardian;
import net.e6tech.elements.common.util.SystemException;
import net.e6tech.elements.network.cluster.AsyncImpl;
import net.e6tech.elements.network.cluster.ClusterAsync;
import net.e6tech.elements.network.cluster.ClusterNode;
import net.e6tech.elements.network.cluster.RouteListener;
import scala.concurrent.ExecutionContextExecutor;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;
import java.util.function.Function;

public class RegistryImpl implements Registry {
    private static String path = "registry";
    public static final String REGISTRY_DISPATCHER = "registry-dispatcher";
    private Guardian guardian;
    private Registrar registrar;
    private ExecutionContextExecutor dispatcher;
    private long timeout = ClusterNode.DEFAULT_TIME_OUT;
    private List<RouteListener> listeners = new ArrayList<>();

    public static String getPath() {
        return path;
    }

    public static void setPath(String path) {
        RegistryImpl.path = path;
    }

    public long getTimeout() {
        return timeout;
    }

    public void setTimeout(long timeout) {
        this.timeout = timeout;
    }

    public void addRouteListener(RouteListener listener) {
        listeners.add(listener);
    }

    public void removeRouteListener(RouteListener listener) {
        listeners.remove(listener);
    }

    void onAnnouncement(String path) {
        dispatcher.execute(() -> {
            for (RouteListener l : listeners)
                l.onAnnouncement(path);
        });
    }

    void onTerminated(String path, ActorRef actor) {
        dispatcher.execute(() -> {
            for (RouteListener l : listeners)
                l.onTerminated(path, actor.path().toString());
        });
    }

    public Guardian getGuardian() {
        return guardian;
    }

    public void start(Guardian guardian) {
        this.guardian = guardian;
        dispatcher = guardian.getContext().getExecutionContext();
        // Create an Akka system
        registrar = new Registrar(this);
        guardian.spawn(registrar, getPath());
    }

    public void shutdown() {
        registrar.stop();
    }

    public <T> Collection routes(String qualifier, Class<T> interfaceClass) {
        if (!interfaceClass.isInterface())
            throw new IllegalArgumentException("interfaceClass needs to be an interface");
        for (Method method : interfaceClass.getMethods()) {
            Local local = method.getAnnotation(Local.class);
            if (local != null)
                continue;
            String methodName = method.getName();
            if ("hashCode".equals(methodName) && method.getParameterCount() == 0
                    || "equals".equals(methodName) && method.getParameterCount() == 1
                    || "toString".equals(methodName) && method.getParameterCount() == 0) {
                // ignored
            } else {
                String p = fullyQualify(qualifier, interfaceClass, method);
                return routes(p);
            }
        }
        return Collections.emptyList();
    }

    public Collection routes(String path) {
        try {
            InvocationEvents.Response response =(InvocationEvents.Response)  registrar.ask(ref -> new InvocationEvents.Routes(ref, path), timeout)
                    .toCompletableFuture()
                    .get();
            return (Collection) response.getValue();
        } catch (Exception e) {
            throw new SystemException(e);
        }
    }

    @Override
    public <R> void register(String path, BiFunction<ActorRef, Object[], R> function) {
        registrar.ask(ref -> new InvocationEvents.Registration(path,  (BiFunction<ActorRef, Object[], Object>)function), timeout);
    }

    @Override
    public <T> void register(String qualifier, Class<T> interfaceClass, T implementation) {
        register(qualifier, interfaceClass, implementation, null);
    }

    /**
     *
     * @param qualifier a unique name for the service
     * @param interfaceClass Interface class.  Its methods will be registered and, therefore, it is important
     *                       for the qualifier to be unique.
     * @param implementation implementation of the interface
     * @param <T> type of implementation
     */
    @SuppressWarnings({"squid:S1067", "squid:S3776"})
    @Override
    public <T> void register(String qualifier, Class<T> interfaceClass, T implementation, Invoker customizedInvoker) {
        if (!interfaceClass.isInterface())
            throw new IllegalArgumentException("interfaceClass needs to be an interface");

        for (Method method : interfaceClass.getMethods()) {
            Local local = method.getAnnotation(Local.class);
            if (local != null)
                continue;
            String methodName = method.getName();
            if ("hashCode".equals(methodName) && method.getParameterCount() == 0
                    || "equals".equals(methodName) && method.getParameterCount() == 1
                    || "toString".equals(methodName) && method.getParameterCount() == 0) {
                // ignored
            } else {
                if (customizedInvoker == null) {
                    customizedInvoker = new Invoker();
                }
                Invoker invoker = customizedInvoker;
                register(fullyQualify(qualifier, interfaceClass, method),
                        (actor, args) -> invoker.invoke(actor, implementation, method, args));
            }
        }
    }

    String fullyQualify(String qualifier, Class interfaceClass, Method method) {
        StringBuilder builder = new StringBuilder();
        String normalizedQualifier = (qualifier == null) ? "" : qualifier.trim();
        if (normalizedQualifier.length() > 0) {
            builder.append(normalizedQualifier);
            builder.append("@");
        }
        builder.append(interfaceClass.getName());
        builder.append("::");
        builder.append(method.getName());
        builder.append("(");
        boolean first = true;
        for (Class param : method.getParameterTypes()) {
            if (first)
                first = false;
            else {
                builder.append(",");
            }
            builder.append(param.getTypeName());
        }
        builder.append(")");
        return builder.toString();
    }

    public Function<Object[], CompletionStage<InvocationEvents.Response>> route(String qualifier, Class interfaceClass, Method method, long timeout) {
        return route(fullyQualify(qualifier, interfaceClass, method), timeout);
    }

    public Function<Object[], CompletionStage<InvocationEvents.Response>> route(String path, long timeout) {
        return arguments ->
            registrar.ask(ref -> new InvocationEvents.Request(ref, path, timeout, arguments), timeout);
    }

    public <T> ClusterAsync<T> async(String qualifier, Class<T> interfaceClass) {
        return new AsyncImpl<>(this, qualifier, interfaceClass, getTimeout());
    }

    public <T> ClusterAsync<T> async(String qualifier, Class<T> interfaceClass, long timeout) {
        return new AsyncImpl<>(this, qualifier, interfaceClass, timeout);
    }
}
