/*
 * Copyright 2017 Futeh Kao
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

package net.e6tech.elements.cassandra.etl;

import net.e6tech.elements.cassandra.generator.Checkpoint;
import net.e6tech.elements.cassandra.generator.Generator;
import net.e6tech.elements.common.reflection.Accessor;
import net.e6tech.elements.common.reflection.Accessors;
import net.e6tech.elements.common.util.SystemException;

import java.beans.PropertyDescriptor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class Inspector {
    private Generator generator;
    private Class sourceClass;
    private boolean initialized = false;
    private TimeUnit timeUnit;
    private List<ColumnAccessor> partitionKeys = new LinkedList<>();
    private List<ColumnAccessor> clusteringKeys = new LinkedList<>();
    private List<ColumnAccessor> checkpoints = new LinkedList<>();
    private List<ColumnAccessor> primaryKeyColumns = new LinkedList<>();
    private Accessors<ColumnAccessor> accessors;
    private List<ColumnAccessor> columns;
    private Map<String, ColumnAccessor> columnMap;


    public Inspector(Class sourceClass, Generator generator) {
        this.sourceClass = sourceClass;
        this.generator = generator;
    }

    public Generator getGenerator() {
        return generator;
    }

    public Class getSourceClass() {
        return sourceClass;
    }

    public TimeUnit getTimeUnit() {
        return timeUnit;
    }

    public void addPartitionKey(ColumnAccessor descriptor) {
        partitionKeys.add(descriptor);
        Collections.sort(partitionKeys, Comparator.comparingInt(p -> p.position));
    }

    public void addClusteringKey(ColumnAccessor descriptor) {
        clusteringKeys.add(descriptor);
        Collections.sort(clusteringKeys, Comparator.comparingInt(p -> p.position));
    }

    public int getPartitionKeySize() {
        return partitionKeys.size();
    }

    public String getPartitionKeyColumn(int n) {
        if (partitionKeys.size() <= n)
            return null;
        return partitionKeys.get(n).columnName;
    }

    public Class getPartitionKeyClass(int n) {
        return getKeyClass(partitionKeys, n);
    }

    private Class getKeyClass(List<ColumnAccessor> keys, int n) {
        if (keys.size() <= n)
            return null;
        ColumnAccessor descriptor =  keys.get(n);
        return descriptor.getType();
    }

    public Object getPartitionKey(Object object, int n) {
        return getKey(partitionKeys, object, n);
    }

    private Object getKey(List<ColumnAccessor> keys, Object object, int n) {
        if (keys.size() <= n)
            return null;
        return keys.get(n).get(object);
    }

    public String getCheckpointColumn(int n) {
        if (checkpoints.size() <= n)
            return null;
        return checkpoints.get(n).columnName;
    }

    public Comparable getCheckpoint(Object object, int n) {
        if (checkpoints.size() <= n)
            return null;
        return (Comparable) checkpoints.get(n).get(object);
    }

    public void setCheckpoint(Object object, int n, Comparable value) {
        if (checkpoints.size() <= n)
            return;
        checkpoints.get(n).set(object, value);
    }

    public int getCheckpointSize() {
        return checkpoints.size();
    }

    public String getClusteringKeyColumn(int n) {
        if (clusteringKeys.size() <= n)
            return null;
        return clusteringKeys.get(n).columnName;
    }

    public Class getClusteringKeyClass(int n) {
        return getKeyClass(clusteringKeys, n);
    }

    public Object getClusteringKey(Object object, int n) {
        return getKey(clusteringKeys, object, n);
    }

    public int getClusteringKeySize() {
        return clusteringKeys.size();
    }

    public String tableName() {
        return generator.tableName(sourceClass);
    }

    public void setPrimaryKey(PrimaryKey key, Object object) {
        try {
            int idx = 0;
            for (ColumnAccessor descriptor : partitionKeys) {
                if (key.length() > idx) {
                    descriptor.set(object, key.get(idx));
                    idx++;
                } else {
                    break;
                }
            }
            for (ColumnAccessor descriptor : clusteringKeys) {
                if (key.length() > idx) {
                    descriptor.set(object, key.get(idx));
                    idx++;
                } else {
                    break;
                }
            }
        } catch (Exception e) {
            throw new SystemException(e);
        }
    }

    public PrimaryKey getPrimaryKey(Object object) {
        try {
            List list = new ArrayList();
            for (ColumnAccessor descriptor : partitionKeys) {
                list.add(descriptor.get(object));
            }
            for (ColumnAccessor descriptor : clusteringKeys) {
                list.add(descriptor.get(object));
            }
            return new PrimaryKey(list.toArray(new Object[0]));
        } catch (Exception e) {
           throw new SystemException(e);
        }
    }

    public List<ColumnAccessor> getPrimaryKeyColumns() {
        return primaryKeyColumns;
    }

    public List<ColumnAccessor> getColumns() {
        return columns;
    }

    public ColumnAccessor getColumn(String column) {
        return columnMap.get(column);
    }

    private ColumnAccessor alloc(int position, Field field) {
        Generator gen = getGenerator();
        ColumnAccessor descriptor = new ColumnAccessor(position, gen.getColumnName(field), field.getName(), field);
        field.setAccessible(true);
        return descriptor;
    }

    private ColumnAccessor alloc(int position, Field field, List<ColumnAccessor> list, Map<String, ColumnAccessor> map) {
        ColumnAccessor descriptor = alloc(position, field);
        map.put(field.getName(), descriptor);
        map.put(descriptor.columnName, descriptor);
        list.add(descriptor);
        return descriptor;
    }

    private ColumnAccessor alloc(int position, PropertyDescriptor desc) {
        Generator gen = getGenerator();
        ColumnAccessor descriptor = new ColumnAccessor(position, gen.getColumnName(desc), desc.getName(), desc);
        return  descriptor;
    }

    private ColumnAccessor alloc(int position, PropertyDescriptor desc, List<ColumnAccessor> list, Map<String, ColumnAccessor> map) {
        ColumnAccessor descriptor = alloc(position, desc);
        map.put(desc.getName(), descriptor);
        map.put(descriptor.columnName, descriptor);
        list.add(descriptor);
        return descriptor;
    }

    @SuppressWarnings({"squid:S3776", "squid:S135"})
    public void initialize() {
        if (initialized)
            return;
        initialized = true;
        Map<String, ColumnAccessor> propertyMap = new HashMap<>(100);
        Map<String, ColumnAccessor> chkMap = new HashMap<>(100);
        Generator gen = getGenerator();
        Class cls = getSourceClass();

        AtomicInteger position = new AtomicInteger(0);
        accessors = new Accessors<>(cls,
                field -> {
                    if (Modifier.isStrict(field.getModifiers())
                        || Modifier.isStatic(field.getModifiers()))
                        return null;
                    if (gen.isTransient(field))
                        return null;
                    if (propertyMap.get(field.getName()) != null)
                        return null;

                    int pk = gen.partitionKeyIndex(field);
                    if (pk >= 0) {
                        alloc(pk, field, partitionKeys, propertyMap);
                        PartitionUnit unit = field.getAnnotation(PartitionUnit.class);
                        if (unit != null && pk == 0)
                            timeUnit = unit.value();
                    }

                    int cc = gen.clusteringColumnIndex(field);
                    if (cc >= 0)
                        alloc(cc, field, clusteringKeys, propertyMap);

                    Checkpoint chk = field.getAnnotation(Checkpoint.class);
                    if (chk != null)
                        alloc(chk.value(), field, checkpoints, chkMap);

                    return alloc(position.getAndIncrement(), field);
                },
                (desc, existing) -> {
                    if (gen.isTransient(desc))
                        return null;
                    if (desc.getName().equals("class"))
                        return null;

                    ColumnAccessor descriptor = propertyMap.get(desc.getName());
                    String columnName = gen.getColumnName(desc);
                    if (descriptor == null) {
                        descriptor = propertyMap.get(columnName);
                    }

                    int pk = gen.partitionKeyIndex(desc);
                    if (pk >= 0 && descriptor == null) {
                        descriptor = alloc(pk, desc, partitionKeys, propertyMap);
                        PartitionUnit unit = Accessor.getAnnotation(desc, PartitionUnit.class);
                        if (unit != null && pk == 0)
                            timeUnit = unit.value();
                    }

                    int cc = gen.clusteringColumnIndex(desc);
                    if (cc >= 0 && descriptor == null) {
                         alloc(cc, desc, clusteringKeys, propertyMap);
                    }

                    Checkpoint chk = Accessor.getAnnotation(desc, Checkpoint.class);
                    ColumnAccessor chkDescriptor = chkMap.get(desc.getName());
                    if (chkDescriptor == null)
                        chkDescriptor = chkMap.get(columnName);
                    if (chk != null && chkDescriptor == null) {
                         alloc(chk.value(), desc, checkpoints, chkMap);
                    }

                    return (existing != null) ? (ColumnAccessor) existing.descriptor(desc)
                            : alloc(position.getAndIncrement(), desc);
                });

        Collections.sort(partitionKeys, Comparator.comparingInt(p -> p.position));
        Collections.sort(clusteringKeys, Comparator.comparingInt(p -> p.position));
        Collections.sort(checkpoints, Comparator.comparingInt(p -> p.position));
        columns = new ArrayList<>(accessors.getAccessors().values());
        Collections.sort(columns, Comparator.comparingInt(p -> p.position));
        for (ColumnAccessor a : partitionKeys) {
            primaryKeyColumns.add(a);
        }
        for (ColumnAccessor a : clusteringKeys) {
            primaryKeyColumns.add(a);
        }

        columnMap = new HashMap<>(columns.size(), 1);
        for (ColumnAccessor a : columns) {
            columnMap.put(a.getColumnName(), a);
        }
    }

    public static class ColumnAccessor extends Accessor {
        int position;
        String columnName;
        String property;

        public ColumnAccessor(int pos, String columnName, String property, Field field) {
            super(field);
            this.position = pos;
            this.columnName = columnName;
            this.property = property;
        }

        public ColumnAccessor(int pos, String columnName, String property, PropertyDescriptor desc) {
            super(desc);
            this.position = pos;
            this.columnName = columnName;
            this.property = property;
        }

        public int getPosition() {
            return position;
        }

        public void setPosition(int position) {
            this.position = position;
        }

        public String getColumnName() {
            return columnName;
        }

        public void setColumnName(String columnName) {
            this.columnName = columnName;
        }

        public String getProperty() {
            return property;
        }

        public void setProperty(String property) {
            this.property = property;
        }
    }
}
