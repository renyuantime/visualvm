/*
 * Copyright (c) 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 * 
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 * 
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 * 
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 * 
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.graalvm.visualvm.heapviewer.truffle;

import org.graalvm.visualvm.heapviewer.truffle.nodes.TerminalJavaNodes;
import org.graalvm.visualvm.heapviewer.HeapFragment;
import org.graalvm.visualvm.heapviewer.java.PrimitiveNode;
import org.graalvm.visualvm.heapviewer.model.DataType;
import org.graalvm.visualvm.heapviewer.model.HeapViewerNode;
import org.graalvm.visualvm.heapviewer.model.HeapViewerNodeFilter;
import org.graalvm.visualvm.heapviewer.model.Progress;
import org.graalvm.visualvm.heapviewer.truffle.dynamicobject.DynamicObject;
import org.graalvm.visualvm.heapviewer.truffle.dynamicobject.DynamicObjectFieldNode;
import org.graalvm.visualvm.heapviewer.truffle.dynamicobject.DynamicObjectReferenceNode;
import org.graalvm.visualvm.heapviewer.ui.UIThresholds;
import org.graalvm.visualvm.heapviewer.utils.NodesComputer;
import org.graalvm.visualvm.heapviewer.utils.ProgressIterator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.accessibility.AccessibleContext;
import javax.swing.JComponent;
import javax.swing.SortOrder;
import org.graalvm.visualvm.heapviewer.HeapContext;
import org.graalvm.visualvm.heapviewer.java.InstanceNode;
import org.graalvm.visualvm.heapviewer.model.TextNode;
import org.graalvm.visualvm.heapviewer.ui.HeapViewerRenderer;
import org.graalvm.visualvm.lib.jfluid.heap.Field;
import org.netbeans.api.progress.ProgressHandle;
import org.graalvm.visualvm.lib.jfluid.heap.FieldValue;
import org.graalvm.visualvm.lib.jfluid.heap.Heap;
import org.graalvm.visualvm.lib.jfluid.heap.HeapProgress;
import org.graalvm.visualvm.lib.jfluid.heap.Instance;
import org.graalvm.visualvm.lib.jfluid.heap.ObjectFieldValue;
import org.graalvm.visualvm.lib.jfluid.heap.PrimitiveArrayInstance;
import org.graalvm.visualvm.lib.profiler.api.icons.Icons;
import org.graalvm.visualvm.lib.profiler.api.icons.ProfilerIcons;
import org.graalvm.visualvm.lib.ui.swing.renderer.NormalBoldGrayRenderer;
import org.openide.util.NbBundle;
import org.openide.util.lookup.ServiceProvider;

/**
 *
 * @author Jiri Sedlacek
 */
@NbBundle.Messages({
    "TruffleObjectPropertyProvider_ComputingNodes=<Computing {0}...>", // <Computing items...>
    "TruffleObjectPropertyProvider_MoreNodes=<another {0} {1} left>", // <another 1234 items left>
    "TruffleObjectPropertyProvider_SamplesContainer=<sample {0} {1}>", // <sample 1234 items>
    "TruffleObjectPropertyProvider_NodesContainer=<{2} {0}-{1}>", // <items 1001 - 2000>
    "TruffleObjectPropertyProvider_OOMEWarning=<too many references - increase heap size!>"
})
public abstract class TruffleObjectPropertyProvider<O extends TruffleObject, T extends TruffleType<O>, F extends TruffleLanguageHeapFragment<O, T>, L extends TruffleLanguage<O, T, F>, I> extends HeapViewerNode.Provider {
    
    private final Class<O> objectClass;
    
    private final L language;
    
    private final String propertyName;
    private final int maxPropertyItems;
    
    private final boolean displaysProgress;
    private final boolean filtersProperties;
    
    
    protected TruffleObjectPropertyProvider(String propertyName, L language, boolean displaysProgress, boolean filtersProperties, int maxPropertyItems) {
        this.language = language;
        this.objectClass = language.getLanguageObjectClass();
        this.propertyName = propertyName;
        this.maxPropertyItems = maxPropertyItems;
        this.displaysProgress = displaysProgress;
        this.filtersProperties = filtersProperties;
    }
    

    @Override
    public String getName() {
        return propertyName;
    }
    
    
    protected final L getLanguage() {
        return language;
    }

    
    @Override
    public abstract boolean supportsView(Heap heap, String viewID);

    @Override
    public abstract boolean supportsNode(HeapViewerNode node, Heap heap, String viewID);
    
    
    protected abstract Collection<I> getPropertyItems(O object, Heap heap);
    
    protected boolean includeItem(I item) { return true; }
    
    protected abstract HeapViewerNode createNode(I item, Heap heap);

    
    @Override
    public final HeapViewerNode[] getNodes(HeapViewerNode parent, Heap heap, String viewID, HeapViewerNodeFilter viewFilter, List<DataType> dataTypes, List<SortOrder> sortOrders, Progress progress) {
        O object = getObject(parent, heap);
        return object == null ? null : getNodes(object, parent, heap, viewID, viewFilter, dataTypes, sortOrders, progress);
    }
    
    final HeapViewerNode[] getNodes(O object, HeapViewerNode parent, Heap heap, String viewID, HeapViewerNodeFilter viewFilter, List<DataType> dataTypes, List<SortOrder> sortOrders, Progress progress) {
        Collection<I> itemsC = null;
        
        if (!displaysProgress) {
            itemsC = getPropertyItems(object, heap);
        } else {
            ProgressHandle pHandle = ProgressHandle.createHandle(Bundle.TruffleObjectPropertyProvider_ComputingNodes(propertyName));
            pHandle.setInitialDelay(1000);
            pHandle.start(HeapProgress.PROGRESS_MAX);
            HeapFragment.setProgress(pHandle, 0);

            try { itemsC = getPropertyItems(object, heap); }
            finally { pHandle.finish(); }
        }
        
        if (itemsC == null) return null;
        
        final List<I> items = new ArrayList(itemsC);
        
        if (filtersProperties) {
            Iterator<I> itemsIt = items.iterator();
            while (itemsIt.hasNext()) if (!includeItem(itemsIt.next())) itemsIt.remove();
        }
        
        NodesComputer<Integer> computer = new NodesComputer<Integer>(items.size(), maxPropertyItems) {
            protected boolean sorts(DataType dataType) {
                return !DataType.COUNT.equals(dataType);
            }
            protected HeapViewerNode createNode(Integer index) {
                return TruffleObjectPropertyProvider.this.createNode(items.get(index), heap);
            }
            protected ProgressIterator<Integer> objectsIterator(int index, Progress progress) {
                Iterator<Integer> iterator = integerIterator(index, items.size());
                return new ProgressIterator(iterator, index, false, progress);
            }
            protected String getMoreNodesString(String moreNodesCount)  {
                return Bundle.TruffleObjectPropertyProvider_MoreNodes(moreNodesCount, propertyName);
            }
            protected String getSamplesContainerString(String objectsCount)  {
                return Bundle.TruffleObjectPropertyProvider_SamplesContainer(objectsCount, propertyName);
            }
            protected String getNodesContainerString(String firstNodeIdx, String lastNodeIdx)  {
                return Bundle.TruffleObjectPropertyProvider_NodesContainer(firstNodeIdx, lastNodeIdx, propertyName);
            }
        };

        return computer.computeNodes(parent, heap, viewID, null, dataTypes, sortOrders, progress);
    }
    
    protected HeapViewerNode[] getNodes(TruffleObjectsWrapper<O> objects, HeapViewerNode parent, Heap heap, String viewID, HeapViewerNodeFilter viewFilter, List<DataType> dataTypes, List<SortOrder> sortOrders, Progress progress) {
        return null;
    }
    
    final O getObject(HeapViewerNode node, Heap heap) {
        if (node == null) return null;
            
        TruffleObject object = HeapViewerNode.getValue(node, TruffleObject.DATA_TYPE, heap);
        if (object == null || !objectClass.isInstance(object)) return null;
        
        return (O)object;
    }
    
    
    public static abstract class Fields<O extends TruffleObject, T extends TruffleType<O>, F extends TruffleLanguageHeapFragment<O, T>, L extends TruffleLanguage<O, T, F>> extends TruffleObjectPropertyProvider<O, T, F, L, FieldValue> {
        
        protected Fields(String propertyName, L language, boolean filtersProperties) {
            super(propertyName, language, false, filtersProperties, UIThresholds.MAX_INSTANCE_FIELDS);
        }
        
        
//        protected abstract boolean isLanguageObject(Instance instance);
//        
//        protected abstract O createObject(Instance instance);
    
        protected abstract HeapViewerNode createObjectFieldNode(O object, String type, FieldValue field);
        
        
        @Override
        protected boolean includeItem(FieldValue field) {
            // display primitive fields
            if (!(field instanceof ObjectFieldValue)) return true;

            Instance instance = ((ObjectFieldValue)field).getInstance();

            // display null fields
            if (instance == null) return true;
            
            // display primitive arrays
            if (instance instanceof PrimitiveArrayInstance) return true;
            
            // display language objects
            if (getLanguage().isLanguageObject(instance)) return true;

            // display DynamicObject fields
            if (DynamicObject.isDynamicObject(instance)) return true;

            // display selected Java fields
            return includeInstance(instance);
        }
        
        protected boolean includeInstance(Instance instance) { return true; }
        
        @Override
        protected final HeapViewerNode createNode(FieldValue field, Heap heap) {
            if (field instanceof ObjectFieldValue) {
                Instance instance = ((ObjectFieldValue)field).getInstance();
                if (getLanguage().isLanguageObject(instance)) {
                    O object = getLanguage().createObject(instance);
                    return createObjectFieldNode(object, object.getType(heap), field);
                } else {
                    return createForeignFieldNode(instance, field, heap);
                }
            } else {
                return new PrimitiveNode.Field(field);
            }
        }
        
        protected HeapViewerNode createForeignFieldNode(Instance instance, FieldValue field, Heap heap) {
            if (DynamicObject.isDynamicObject(instance)) {
                DynamicObject dobj = new DynamicObject(instance);
                return new DynamicObjectFieldNode(dobj, dobj.getType(heap), field);
            } else {
                return new TerminalJavaNodes.Field((ObjectFieldValue)field, false);
            }
        }
        
        @Override
        protected HeapViewerNode[] getNodes(TruffleObjectsWrapper<O> objects, HeapViewerNode parent, Heap heap, String viewID, HeapViewerNodeFilter viewFilter, List<DataType> dataTypes, List<SortOrder> sortOrders, Progress progress) {
            final Set<String> fields = getAllObjectsFields(objects, heap, progress);
            NodesComputer<String> computer = new NodesComputer<String>(fields.size(), UIThresholds.MAX_INSTANCE_FIELDS) {
                protected boolean sorts(DataType dataType) {
                    return true;
                }
                protected HeapViewerNode createNode(String field) {
                    return new MergedObjectPropertyNode(field);
                }
                protected ProgressIterator<String> objectsIterator(int index, Progress progress) {
                    Iterator<String> iterator = fields.iterator();
                    return new ProgressIterator(iterator, index, true, progress);
                }
                protected String getMoreNodesString(String moreNodesCount)  {
                    return Bundle.TruffleObjectPropertyProvider_MoreNodes(moreNodesCount, getName());
                }
                protected String getSamplesContainerString(String objectsCount)  {
                    return Bundle.TruffleObjectPropertyProvider_SamplesContainer(objectsCount, getName());
                }
                protected String getNodesContainerString(String firstNodeIdx, String lastNodeIdx)  {
                    return Bundle.TruffleObjectPropertyProvider_NodesContainer(firstNodeIdx, lastNodeIdx, getName());
                }
            };
            return computer.computeNodes(parent, heap, viewID, null, dataTypes, sortOrders, progress);
        }
        
        
        private Set<String> getAllObjectsFields(TruffleObjectsWrapper<O> objects, Heap heap, Progress progress) {
            progress.setupKnownSteps(objects.getObjectsCount());
            
            Set<String> allFields = new HashSet();
            Iterator<O> objectsI = objects.getObjectsIterator();
            while (objectsI.hasNext()) {
                progress.step();
                Collection<FieldValue> fields = getPropertyItems(objectsI.next(), heap);
                if (fields != null) for (FieldValue field : fields) {
                    Field f = field.getField();
                    allFields.add(f.isStatic() ? "static " + f.getName() : f.getName());
                }
            }
            
            progress.finish();
            
            return allFields;
        }
        
    }
    
    
    public static abstract class References<O extends TruffleObject, T extends TruffleType<O>, F extends TruffleLanguageHeapFragment<O, T>, L extends TruffleLanguage<O, T, F>> extends TruffleObjectPropertyProvider<O, T, F, L, FieldValue> {
        
        protected References(String propertyName, L language, boolean filtersProperties) {
            super(propertyName, language, true, filtersProperties, UIThresholds.MAX_INSTANCE_REFERENCES);
        }
        
        
        protected abstract HeapViewerNode createObjectReferenceNode(O object, String type, FieldValue field);
        
        
        @Override
        protected boolean includeItem(FieldValue field) {
            Instance instance = field.getDefiningInstance();

            // should not happen
            if (instance == null) return false;
            
            // display language references
            if (getLanguage().isLanguageObject(instance)) return true;

            // display DynamicObject references
            if (DynamicObject.isDynamicObject(instance)) return true;

            // display selected Java references
            return includeInstance(instance);
        }
        
        protected boolean includeInstance(Instance instance) { return true; }
        
        @Override
        protected final HeapViewerNode createNode(FieldValue field, Heap heap) {
            Instance instance = field.getDefiningInstance();
            if (getLanguage().isLanguageObject(instance)) {
                O object = getLanguage().createObject(instance);
                return new MergedObjectReferenceNode(createObjectReferenceNode(object, object.getType(heap), field));
            } else {
                return new MergedObjectReferenceNode(createForeignReferenceNode(instance, field, heap));
            }
        }
        
        protected HeapViewerNode createForeignReferenceNode(Instance instance, FieldValue field, Heap heap) {
            if (DynamicObject.isDynamicObject(instance)) {
                DynamicObject dobj = new DynamicObject(instance);
                return new DynamicObjectReferenceNode(dobj, dobj.getType(heap), field);
            } else {
                return new TerminalJavaNodes.Field((ObjectFieldValue)field, true);
            }
        }
        
        
        // TODO: set to false or replace by Thread.interrupt() when the TruffleObjectPropertyPlugin selection changes!
        private volatile boolean computingChildren;
                                    
        @Override
        protected HeapViewerNode[] getNodes(TruffleObjectsWrapper<O> objects, HeapViewerNode parent, Heap heap, String viewID, HeapViewerNodeFilter viewFilter, List<DataType> dataTypes, List<SortOrder> sortOrders, Progress progress) {

            final Map<Long, Integer> values = new HashMap();

            progress.setupKnownSteps(objects.getObjectsCount());
            
            Iterator<O> objectsI = objects.getObjectsIterator();
            try {
                computingChildren = true;
                while (computingChildren && objectsI.hasNext()) {
                    O object = objectsI.next();
                    progress.step();
                    Collection<FieldValue> references = getPropertyItems(object, heap);
                    Set<Instance> referers = new HashSet();
                    for (FieldValue reference : references) {
                        if (!computingChildren) break;
                        if (includeItem(reference)) referers.add(reference.getDefiningInstance());
                    }
                    for (Instance referer : referers) {
                        if (!computingChildren) break;
                        long refererID = referer.getInstanceId();
                        Integer count = values.get(refererID);
                        if (count == null) count = 0;
                        values.put(refererID, ++count);
                    }
                }
                if (!computingChildren) return null;
            } catch (OutOfMemoryError e) {
                return new HeapViewerNode[] { new TextNode(Bundle.TruffleObjectPropertyProvider_OOMEWarning()) };
            } finally {
                computingChildren = false;
            }

            progress.finish();
            
            final L language = getLanguage();

            NodesComputer<Map.Entry<Long, Integer>> computer = new NodesComputer<Map.Entry<Long, Integer>>(values.size(), UIThresholds.MAX_CLASS_INSTANCES) {
                protected boolean sorts(DataType dataType) {
                    return true;
                }
                protected HeapViewerNode createNode(final Map.Entry<Long, Integer> node) {
                    Instance instance = heap.getInstanceByID(node.getKey());
                    if (language.isLanguageObject(instance)) {
                        O object = language.createObject(instance);
                        return new MergedObjectReferenceNode((HeapViewerNode)language.createObjectNode(object, object.getType(heap)));
                    } else {
                        return new MergedObjectReferenceNode(new InstanceNode(instance));
                    }
                }
                protected ProgressIterator<Map.Entry<Long, Integer>> objectsIterator(int index, Progress progress) {
                    Iterator<Map.Entry<Long, Integer>> iterator = values.entrySet().iterator();
                    return new ProgressIterator(iterator, index, true, progress);
                }
                protected String getMoreNodesString(String moreNodesCount)  {
                    return Bundle.TruffleObjectPropertyProvider_MoreNodes(moreNodesCount, getName());
                }
                protected String getSamplesContainerString(String objectsCount)  {
                    return Bundle.TruffleObjectPropertyProvider_SamplesContainer(objectsCount, getName());
                }
                protected String getNodesContainerString(String firstNodeIdx, String lastNodeIdx)  {
                    return Bundle.TruffleObjectPropertyProvider_NodesContainer(firstNodeIdx, lastNodeIdx, getName());
                }
            };

            return computer.computeNodes(parent, heap, viewID, null, dataTypes, sortOrders, progress);
        }
        
    }
    
    
    private static class MergedObjectPropertyNode extends HeapViewerNode {
        
        private final String name;
        
        private final int count;
        
        
        MergedObjectPropertyNode(String name) {
            this(name, -1);
        }
        
        MergedObjectPropertyNode(String name, int count) {
            this.name = name;
            this.count = count;
        }
        
        
        String getName() {
            return name;
        }
        
        int getCount() {
            return count;
        }
        
        
        public String toString() {
            return getName();
        }
        
        
        public final boolean isLeaf() {
            return true;
        }
        
    }
    
    private static class MergedObjectPropertyNodeRenderer extends NormalBoldGrayRenderer implements HeapViewerRenderer {
        
        public void setValue(Object value, int row) {
            if (value instanceof MergedObjectPropertyNode) {
                MergedObjectPropertyNode node = (MergedObjectPropertyNode)value;
                
                String name = node.getName();
                boolean staticv = name.startsWith("static "); // NOI18N
                
                setNormalValue(staticv ? "static " : ""); // NOI18N
                setBoldValue(staticv ? name.substring("static ".length()) : name); // NOI18N
                setGrayValue(""); // NOI18N
                
                setIcon(Icons.getIcon(ProfilerIcons.NODE_FORWARD));
            }
        }
        
    }
    
    
    private static class MergedObjectReferenceNode extends HeapViewerNode {
        
        final HeapViewerNode reference;
        
        
        MergedObjectReferenceNode(HeapViewerNode reference) {
            this.reference = reference;
        }
        
        
        public String toString() {
            return reference.toString();
        }
        
        protected Object getValue(DataType type, Heap heap) {
            return HeapViewerNode.getValue(reference, type, heap);
        }
        
        
        public final boolean isLeaf() {
            return true;
        }
        
    }
    
    private static class MergedObjectReferenceNodeRenderer implements HeapViewerRenderer {
        
        private HeapViewerRenderer current;
        
        @Override
        public void setValue(Object value, int row) {
            HeapViewerNode node = ((MergedObjectReferenceNode)value).reference;
            current = TruffleObjectPropertyPlugin.resolveRenderer(node);
            current.setValue(node, row);
        }

        @Override
        public int getHorizontalAlignment() {
            return current.getHorizontalAlignment();
        }

        @Override
        public JComponent getComponent() {
            return current.getComponent();
        }

        @Override
        public void move(int x, int y) {
            current.move(x, y);
        }

        @Override
        public AccessibleContext getAccessibleContext() {
            return current.getAccessibleContext();
        }
        
    }
    
    
    @ServiceProvider(service=HeapViewerRenderer.Provider.class)
    public static class MergedObjectPropertyNodeRendererProvider extends HeapViewerRenderer.Provider {

        @Override
        public boolean supportsView(HeapContext context, String viewID) {
            return true;
        }

        @Override
        public void registerRenderers(Map<Class<? extends HeapViewerNode>, HeapViewerRenderer> renderers, HeapContext context) {
            renderers.put(MergedObjectPropertyNode.class, new MergedObjectPropertyNodeRenderer());
            renderers.put(MergedObjectReferenceNode.class, new MergedObjectReferenceNodeRenderer());
        }
        
    }
    
}
