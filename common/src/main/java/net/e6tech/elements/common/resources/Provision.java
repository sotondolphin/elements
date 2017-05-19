/*
Copyright 2015 Futeh Kao

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package net.e6tech.elements.common.resources;

import net.e6tech.elements.common.logging.Logger;
import net.e6tech.elements.common.reflection.ObjectConverter;
import net.e6tech.elements.common.resources.plugin.Path;
import net.e6tech.elements.common.resources.plugin.Pluggable;
import net.e6tech.elements.common.resources.plugin.Plugin;

import javax.inject.Inject;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Created by futeh.
 */
public class Provision implements Transactional {

    @Inject
    private ResourceManager resourceManager;

    public Provision load(Map<String, Object> map) {
        ObjectConverter converter = new ObjectConverter();
        Class cls = getClass();
        while (Provision.class.isAssignableFrom(cls)) {
            Field[] fields = cls.getDeclaredFields();
            for (Field f : fields) {
                if (Modifier.isPublic(f.getModifiers()) && !Modifier.isStatic(f.getModifiers())) {
                    if (map.get(f.getName()) != null) {
                        Object from = map.get(f.getName());
                        if (from != null) {
                            try {
                                f.setAccessible(true);
                                f.set(this, converter.convert(from, f, null));
                                f.setAccessible(false);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        }
                    }
                }
            }
            cls = cls.getSuperclass();
        }
        return this;
    }

    public void log(Logger logger, String message, Throwable th) {
        logger.warn(message, th);
    }

    public ResourceManager getResourceManager() {
        return resourceManager;
    }

    public <T> T getComponentResource(String componentName, String resourceName) {
        return getResourceManager().getAtomResource(componentName, resourceName);
    }

    public String getName() {
        return resourceManager.getName();
    }

    public <T> T  getVariable(String key) {
        return resourceManager.getVariable(key);
    }

    public Properties getProperties() {
        return resourceManager.getProperties();
    }

    public Map<String, List<String>> getKnownEnvironments() {
        return resourceManager.getKnownEnvironments();
    }

    public <T> T getBean(String name) {
        return resourceManager.getBean(name);
    }

    public <T> T getBean(Class<T> cls) {
        return resourceManager.getBean(cls);
    }

    public <T> T getInstance(Class<T> cls) throws InstanceNotFoundException {
        return resourceManager.getInstance(cls);
    }

    public <T> T newInstance(Class<T> cls) {
        return resourceManager.newInstance(cls);
    }

    public <T> T inject(T obj) {
        return resourceManager.inject(obj);
    }

    public <Res extends Resources> Res open() {
        return resourceManager.open(null);
    }

    public <Res extends Resources> Res open(Map configuration) {
        return resourceManager.open(configuration);
    }

    public Class<? extends Resources> getResourcesClass() {
        return Resources.class;
    }

    public <T extends Pluggable> T getPlugin(Class c1, String n1, Class c2, Object ... args) {
        return (T) getPlugin(Path.of(c1, n1).and(c2), args);
    }

    public <T extends Pluggable> T getPlugin(Class c1, String n1, Class c2, String n2, Class c3, Object ... args) {
        return (T) getPlugin(Path.of(c1, n1).and(c2, n2).and(c3), args);
    }

    public <T extends Pluggable> T getPlugin(Path<T> path, Object ... args) {
        return (T) getInstance(Plugin.class).get(path, args);
    }

    // used for configuring resourcesManager's resourceProviders before Resources is open
    public UnitOfWork preOpen(Consumer<Resources> consumer) {
        UnitOfWork unitOfWork = new UnitOfWork(resourceManager);
        return unitOfWork.preOpen(consumer);
    }

    public UnitOfWork onOpen(OnOpen onOpen) {
        UnitOfWork unitOfWork = new UnitOfWork(resourceManager);
        return unitOfWork.onOpen(onOpen);
    }

    public ResourcesFactory resourcesFactory() {
        ResourcesFactory factory = new ResourcesFactory();
        inject(factory);
        return factory;
    }
}
