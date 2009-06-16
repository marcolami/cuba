/*
 * Copyright (c) 2008 Haulmont Technology Ltd. All Rights Reserved.
 * Haulmont Technology proprietary and confidential.
 * Use is subject to license terms.

 * Author: Dmitry Abramov
 * Created: 06.04.2009 11:44:49
 * $Id$
 */
package com.haulmont.cuba.gui.xml.layout.loaders;

import com.haulmont.chile.core.model.MetaClass;
import com.haulmont.chile.core.model.MetaPropertyPath;
import com.haulmont.cuba.gui.components.Component;
import com.haulmont.cuba.gui.components.Table;
import com.haulmont.cuba.gui.data.CollectionDatasource;
import com.haulmont.cuba.gui.data.Datasource;
import com.haulmont.cuba.gui.xml.layout.ComponentsFactory;
import com.haulmont.cuba.gui.xml.layout.LayoutLoaderConfig;
import org.apache.commons.lang.BooleanUtils;
import org.apache.commons.lang.StringUtils;
import org.dom4j.Element;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public abstract class AbstractTableLoader<T extends Table> extends ComponentLoader {
    protected ComponentsFactory factory;
    protected LayoutLoaderConfig config;

    public AbstractTableLoader(Context context, ComponentsFactory factory, LayoutLoaderConfig config) {
        super(context);
        this.factory = factory;
        this.config = config;
    }

    public Component loadComponent(ComponentsFactory factory, Element element) throws InstantiationException, IllegalAccessException
    {
        final T component = createComponent(factory);

        assignXmlDescriptor(component, element);
        loadId(component, element);
        loadVisible(component, element);
        loadEditable(component, element);

        loadStyleName(component, element);

        loadHeight(component, element);
        loadWidth(component, element);

        final Element columnsElement = element.element("columns");
        final Element rowsElement = element.element("rows");

        final String rowHeaderMode = rowsElement.attributeValue("headerMode");
        if (!StringUtils.isEmpty(rowHeaderMode)) {
            component.setRowHeaderMode(Table.RowHeaderMode.valueOf(rowHeaderMode));
        }

        final String datasource = rowsElement.attributeValue("datasource");

        if (!StringUtils.isBlank(datasource)) {
            final CollectionDatasource ds = context.getDSContext().get(datasource);
            List<Table.Column> availableColumns = new ArrayList<Table.Column>();

            if (columnsElement != null) {
                //noinspection unchecked
                for (Element columnElement : (Collection<Element>)columnsElement.elements("column")) {
                    String visible = columnElement.attributeValue("visible");
                    if (visible == null) {
                        final Element e = columnElement.element("visible");
                        if (e != null) {
                            visible = e.getText();
                        }
                    }

                    if (StringUtils.isEmpty(visible) || evaluateBoolean(visible)) {
                        availableColumns.add(loadColumn(columnElement, ds));
                    }
                }
            }

            for (Table.Column column : availableColumns) {
                component.addColumn(column);
            }

            component.setDatasource(ds);
        } else {
            throw new UnsupportedOperationException();
        }

        final String multiselect = element.attributeValue("multiselect");
        component.setMultiSelect(BooleanUtils.toBoolean(multiselect));
        
        addAssignWindowTask(component);

        return component;
    }

    protected abstract T createComponent(ComponentsFactory factory) throws InstantiationException, IllegalAccessException;

    private Table.Column loadColumn(Element element, Datasource ds) {
        final String id = element.attributeValue("id");

        final MetaClass metaClass = ds.getMetaClass();
        final MetaPropertyPath metaPropertyPath = metaClass.getPropertyEx(id);
        if (metaPropertyPath == null)
            throw new IllegalStateException(String.format("Property '%s' not found in entity '%s'", id, metaClass.getName()));

        final Table.Column column = new Table.Column(metaPropertyPath);

        String editable = element.attributeValue("editable");
        if (editable == null) {
            final Element e = element.element("editable");
            if (e != null) {
                editable = e.getText();
            }
        }

        if (!StringUtils.isEmpty(editable)) {
            column.setEditable(evaluateBoolean(editable));
        }

        loadCaption(column, element);

        column.setXmlDescriptor(element);
        column.setType(metaPropertyPath.getRangeJavaClass());

        return column;
    }
}
