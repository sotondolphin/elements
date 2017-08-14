/*
 * Copyright 2015 Futeh Kao
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

package net.e6tech.elements.common.resources.plugin;

import net.e6tech.elements.common.inject.Injector;
import net.e6tech.elements.common.inject.Module;
import net.e6tech.elements.common.inject.ModuleFactory;
import net.e6tech.elements.common.logging.Logger;
import net.e6tech.elements.common.resources.*;
import net.e6tech.elements.common.util.InitialContextFactory;
import net.e6tech.elements.common.util.SystemException;
import net.e6tech.elements.common.util.file.FileUtil;

import javax.naming.Context;
import javax.naming.NamingException;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;

/**
 * Created by futeh.
 */
@SuppressWarnings({"squid:MethodCyclomaticComplexity", "squid:S134"})
public class PluginManager {

    private static final String DEFAULT_PLUGIN = "defaultPlugin";

    private PluginClassLoader classLoader;
    private Context context;
    private ResourceManager resourceManager;
    private Resources resources;
    private Map<Class, Object> defaultPlugins = new HashMap<>();

    public PluginManager(ResourceManager resourceManager) {
        this.resourceManager = resourceManager;
        classLoader = new PluginClassLoader(resourceManager.getClass().getClassLoader());
        context = (new InitialContextFactory()).createContext(new Hashtable());
    }

    public PluginManager from(Resources resources) {
        PluginManager plugin = new PluginManager(resourceManager);
        plugin.resources = resources;
        plugin.context = context;
        plugin.defaultPlugins = defaultPlugins;
        plugin.classLoader = classLoader;
        return plugin;
    }

    public void loadPlugins(String[] directories) {
        for (String dir: directories) {
            java.nio.file.Path[] paths = new java.nio.file.Path[0];
            try {
                paths = FileUtil.listFiles(dir, "jar");
            } catch (IOException e) {
                throw new SystemException(e);
            }
            for (java.nio.file.Path p : paths) {
                try {
                    classLoader.addURL(p.toUri().toURL());
                } catch (MalformedURLException e) {
                    throw new SystemException(e);
                }
            }
        };
    }

    public ClassLoader getPluginClassLoader() {
        return classLoader;
    }

    public <T extends Plugin> T get(PluginPath<T> path, Object ... args) {
        String fullPath = path.path();
        Object lookup = null;
        try {
            lookup =  context.lookup(fullPath);
        } catch (NamingException e) {
            Logger.suppress(e);
            Class type = path.getType();
            lookup = defaultPlugins.get(type);
            if (lookup == null) {
                while (type != null && !type.equals(Object.class)) {
                    try {
                        Field field = type.getField(DEFAULT_PLUGIN);
                        lookup = field.get(null);
                        defaultPlugins.put(path.getType(), lookup);
                        break;
                    } catch (NoSuchFieldException | IllegalAccessException e1) {
                        Logger.suppress(e1);
                    }
                    type = type.getSuperclass();
                }
                if (lookup == null)
                    throw new SystemException("Invalid plugin path: " + fullPath);
            }
        }

        Plugin plugin;
        if (lookup instanceof Class) {
            try {
                plugin = (T) ((Class) lookup).newInstance();
            } catch (Exception e) {
                throw new SystemException(e);
            }
        } else {
            plugin = (T) lookup;
        }

        if (args != null && args.length > 0) {
            ModuleFactory factory = (resources != null) ? resources.getModule().getFactory() :
                    resourceManager.getModule().getFactory();
            Module module = factory.create();
            for (Object arg : args) {
                if (arg instanceof  Binding) {
                    Binding binding = (Binding) arg;
                    if (binding.getName() != null) {
                        module.bindNamedInstance(binding.getBoundClass(), binding.getName(), binding.get());
                    } else {
                        module.bindInstance(binding.getBoundClass(), binding.get());
                    }
                } else {
                    module.bindInstance(arg.getClass(), arg);
                }
            }

            Injector injector = (resources != null) ?
                    module.build(resources.getModule(), resourceManager.getModule())
                    : module.build(resourceManager.getModule());
            if (plugin instanceof InjectionListener) {
                ((InjectionListener) plugin).preInject(resources);
            }
            injector.inject(plugin);
            if (plugin instanceof InjectionListener) {
                ((InjectionListener) plugin).injected(resources);
            }
        }

        plugin.initialize();
        return (T) plugin;
    }

    public <T extends Plugin> void add(PluginPath<T> path, Class<T> cls) {
        try {
            context.rebind(path.path(), cls);
        } catch (NamingException e) {
            throw new SystemException(e);
        }
    }

    public <T extends Plugin> void add(PluginPath<T> path, T object) {
        try {
            context.rebind(path.path(), object);
        } catch (NamingException e) {
            throw new SystemException(e);
        }
    }

    public <T extends Plugin, U extends T> void addDefault(Class<T> cls, U object) {
        defaultPlugins.put(cls, object);
    }

    public <T extends Plugin, U extends T> void addDefault(Class<T> cls, Class<U> implClass) {
        defaultPlugins.put(cls, implClass);
    }

    public Object removeDefault(Class cls) {
        return defaultPlugins.remove(cls);
    }

    public static class PluginClassLoader extends URLClassLoader {

        public PluginClassLoader(ClassLoader parent) {
            super(new URL[0], parent);
        }

        @Override
        public void addURL(URL url) {
            super.addURL(url);
        }

    }
}