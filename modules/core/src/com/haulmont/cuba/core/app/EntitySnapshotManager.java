/*
 * Copyright (c) 2011 Haulmont Technology Ltd. All Rights Reserved.
 * Haulmont Technology proprietary and confidential.
 * Use is subject to license terms.
 */

package com.haulmont.cuba.core.app;

import com.haulmont.chile.core.model.MetaClass;
import com.haulmont.cuba.core.EntityManager;
import com.haulmont.cuba.core.Persistence;
import com.haulmont.cuba.core.Query;
import com.haulmont.cuba.core.Transaction;
import com.haulmont.cuba.core.entity.BaseEntity;
import com.haulmont.cuba.core.entity.BaseUuidEntity;
import com.haulmont.cuba.core.entity.EntitySnapshot;
import com.haulmont.cuba.core.global.EntityDiff;
import com.haulmont.cuba.core.global.MetadataProvider;
import com.haulmont.cuba.core.global.TimeProvider;
import com.haulmont.cuba.core.global.View;
import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.converters.reflection.ExternalizableConverter;
import org.dom4j.*;

import javax.annotation.ManagedBean;
import javax.inject.Inject;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * <p>$Id$</p>
 *
 * @author artamonov
 */
@ManagedBean(EntitySnapshotAPI.NAME)
@SuppressWarnings({"unchecked", "unused"})
public class EntitySnapshotManager implements EntitySnapshotAPI {

    @Inject
    private Persistence persistence;

    private EntityDiffManager diffManager;

    public EntitySnapshotManager() {
        diffManager = new EntityDiffManager(this);
    }

    @Override
    public List<EntitySnapshot> getSnapshots(MetaClass metaClass, UUID id) {
        List<EntitySnapshot> resultList = null;

        Transaction tx = persistence.createTransaction();
        try {
            EntityManager em = persistence.getEntityManager();
            Query query = em.createQuery(
                    "select s from core$EntitySnapshot s where s.entityId = :entityId and s.entityMetaClass = :metaClass");
            query.setParameter("entityId", id);
            query.setParameter("metaClass", metaClass.getName());
            resultList = query.getResultList();

            tx.commit();
        } finally {
            tx.end();
        }

        return resultList;
    }

    private void replaceInXmlTree(Element element, Map<Class, Class> classMapping) {
        for (int i = 0; i < element.nodeCount(); i++) {
            Node node = element.node(i);
            if (node instanceof Element) {
                Element childElement = (Element) node;
                replaceClasses(childElement, classMapping);
                replaceInXmlTree(childElement, classMapping);
            }
        }
    }

    private void replaceClasses(Element element, Map<Class, Class> classMapping) {
        // translate XML
        for (Map.Entry<Class, Class> classEntry : classMapping.entrySet()) {
            Class beforeClass = classEntry.getKey();
            Class afterClass = classEntry.getValue();

            checkNotNull(beforeClass);
            checkNotNull(afterClass);

            // If BeforeClass != AfterClass
            if (!beforeClass.equals(afterClass)) {
                String beforeClassName = beforeClass.getCanonicalName();
                String afterClassName = afterClass.getCanonicalName();

                if (beforeClassName.equals(element.getName()))
                    element.setName(afterClassName);

                Attribute classAttribute = element.attribute("class");
                if ((classAttribute != null) && beforeClassName.equals(classAttribute.getValue())) {
                    classAttribute.setValue(afterClassName);
                }
            }
        }
    }

    private String processViewXml(String viewXml, Map<Class, Class> classMapping) {
        for (Map.Entry<Class, Class> classEntry : classMapping.entrySet()) {
            Class beforeClass = classEntry.getKey();
            Class afterClass = classEntry.getValue();

            checkNotNull(beforeClass);
            checkNotNull(afterClass);

            String beforeClassName = beforeClass.getCanonicalName();
            String afterClassName = afterClass.getCanonicalName();

            viewXml = viewXml.replaceAll(beforeClassName, afterClassName);
        }
        return viewXml;
    }

    private String processSnapshotXml(String snapshotXml, Map<Class, Class> classMapping) {
        Document document;
        try {
            document = DocumentHelper.parseText(snapshotXml);
        } catch (DocumentException e) {
            throw new RuntimeException("Couldn't parse snapshot xml content", e);
        }
        replaceClasses(document.getRootElement(), classMapping);
        replaceInXmlTree(document.getRootElement(), classMapping);
        return document.asXML();
    }

    @Override
    public void migrateSnapshots(MetaClass metaClass, UUID id, Map<Class, Class> classMapping) {
        // load snapshots
        List<EntitySnapshot> snapshotList = getSnapshots(metaClass, id);
        Class javaClass = metaClass.getJavaClass();

        MetaClass mappedMetaClass = null;
        if (classMapping.containsKey(javaClass)) {
            Class mappedClass = classMapping.get(javaClass);
            mappedMetaClass = MetadataProvider.getSession().getClass(mappedClass);
        }

        for (EntitySnapshot snapshot : snapshotList) {
            if (mappedMetaClass != null)
                snapshot.setEntityMetaClass(mappedMetaClass.getName());

            String snapshotXml = snapshot.getSnapshotXml();
            String viewXml = snapshot.getViewXml();

            snapshot.setSnapshotXml(processSnapshotXml(snapshotXml, classMapping));
            snapshot.setViewXml(processViewXml(viewXml, classMapping));
        }

        // Save snapshots to db
        Transaction tx = persistence.createTransaction();
        try {
            EntityManager em = persistence.getEntityManager();

            for (EntitySnapshot snapshot : snapshotList)
                em.merge(snapshot);

            tx.commit();
        } finally {
            tx.end();
        }
    }

    @Override
    public EntitySnapshot createSnapshot(BaseEntity entity, View view) {
        return createSnapshot(entity, view, TimeProvider.currentTimestamp());
    }

    @Override
    public EntitySnapshot createSnapshot(BaseEntity entity, View view, Date snapshotDate) {
        if (entity == null)
            throw new NullPointerException("Could not be create snapshot for null entity");

        if (view == null)
            throw new NullPointerException("Could not be create snapshot for entity with null view");

        Class viewEntityClass = view.getEntityClass();
        Class entityClass = entity.getClass();

        if (!viewEntityClass.isAssignableFrom(entityClass))
            throw new IllegalStateException("View could not be used with this propertyValue");

        EntitySnapshot snapshot = new EntitySnapshot();
        snapshot.setEntityId(entity.getUuid());

        MetaClass metaClass = MetadataProvider.getSession().getClass(entity.getClass());
        snapshot.setEntityMetaClass(metaClass.getName());

        snapshot.setViewXml(toXML(view));
        snapshot.setSnapshotXml(toXML(entity));
        snapshot.setSnapshotDate(snapshotDate);

        Transaction tx = persistence.createTransaction();
        try {
            EntityManager em = persistence.getEntityManager();
            em.persist(snapshot);

            tx.commit();
        } finally {
            tx.end();
        }

        return snapshot;
    }

    @Override
    public BaseEntity extractEntity(EntitySnapshot snapshot) {
        String xml = snapshot.getSnapshotXml();
        return (BaseUuidEntity) fromXML(xml);
    }

    @Override
    public View extractView(EntitySnapshot snapshot) {
        String xml = snapshot.getViewXml();
        return (View) fromXML(xml);
    }

    @Override
    public EntityDiff getDifference(EntitySnapshot first, EntitySnapshot second) {
        return diffManager.getDifference(first, second);
    }

    private Object fromXML(String xml) {
        XStream xStream = new XStream();
        xStream.getConverterRegistry().removeConverter(ExternalizableConverter.class);
        return xStream.fromXML(xml);
    }

    private String toXML(Object obj) {
        XStream xStream = new XStream();
        xStream.getConverterRegistry().removeConverter(ExternalizableConverter.class);
        return xStream.toXML(obj);
    }
}