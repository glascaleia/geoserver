/* (c) 2014 - 2016 Open Source Geospatial Foundation - all rights reserved
 * (c) 2001 - 2013 OpenPlans
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */
package org.geoserver.web.data.resource;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.wicket.WicketRuntimeException;
import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.ajax.form.AjaxFormComponentUpdatingBehavior;
import org.apache.wicket.markup.html.WebMarkupContainer;
import org.apache.wicket.markup.html.basic.Label;
import org.apache.wicket.markup.html.form.CheckBox;
import org.apache.wicket.markup.html.form.ChoiceRenderer;
import org.apache.wicket.markup.html.form.DropDownChoice;
import org.apache.wicket.markup.html.form.FormComponentPanel;
import org.apache.wicket.markup.html.form.TextField;
import org.apache.wicket.model.IModel;
import org.apache.wicket.model.PropertyModel;
import org.apache.wicket.validation.IValidatable;
import org.apache.wicket.validation.IValidator;
import org.apache.wicket.validation.ValidationError;
import org.geoserver.catalog.CoverageInfo;
import org.geoserver.catalog.DimensionDefaultValueSetting;
import org.geoserver.catalog.DimensionDefaultValueSetting.Strategy;
import org.geoserver.catalog.DimensionInfo;
import org.geoserver.catalog.DimensionPresentation;
import org.geoserver.catalog.FeatureTypeInfo;
import org.geoserver.catalog.ResourceInfo;
import org.geoserver.catalog.impl.DimensionInfoImpl;
import org.geoserver.ows.kvp.ElevationKvpParser;
import org.geoserver.ows.kvp.TimeParser;
import org.geoserver.platform.GeoServerExtensions;
import org.geoserver.web.wicket.ParamResourceModel;
import org.geotools.coverage.grid.io.GridCoverage2DReader;
import org.geotools.util.Range;
import org.geotools.util.logging.Logging;
import org.opengis.coverage.grid.GridCoverageReader;
import org.opengis.feature.type.PropertyDescriptor;

/**
 * Edits a {@link DimensionInfo} object for the specified resource
 * 
 * @author Andrea Aime - GeoSolutions
 */
@SuppressWarnings("serial")
public class DimensionEditor extends FormComponentPanel<DimensionInfo> {
    
    static final Logger LOGGER = Logging.getLogger(DimensionEditor.class);

    List<DimensionPresentation> presentationModes;
    
    List<DimensionDefaultValueSetting.Strategy> defaultValueStrategies;

    private CheckBox enabled;

    private DropDownChoice<String> attribute;
    
    private DropDownChoice<String> endAttribute;

    private DropDownChoice<DimensionPresentation> presentation;
    
    private DropDownChoice<DimensionDefaultValueSetting.Strategy> defaultValueStrategy;

    private TextField<String> referenceValue;
    
    
    private TextField<String> units;
    
    private TextField<String> unitSymbol;
    
    private PeriodEditor resTime;

    private TextField<BigDecimal> resElevation;
    
    boolean time;
    
    public DimensionEditor(String id, IModel<DimensionInfo> model, ResourceInfo resource, Class<?> type) {
        super(id, model);

        // double container dance to get stuff to show up and hide on demand (grrr)
        final WebMarkupContainer configsContainer = new WebMarkupContainer("configContainer");
        configsContainer.setOutputMarkupId(true);
        add(configsContainer);
        final WebMarkupContainer configs = new WebMarkupContainer("configs");
        configs.setOutputMarkupId(true);
        configs.setVisible(getModelObject().isEnabled());
        configsContainer.add(configs);

        // enabled flag, and show the rest only if enabled is true
        final PropertyModel<Boolean> enabledModel = new PropertyModel<Boolean>(model, "enabled");
        enabled = new CheckBox("enabled", enabledModel);
        add(enabled);
        enabled.add(new AjaxFormComponentUpdatingBehavior("click") {

            @Override
            protected void onUpdate(AjaxRequestTarget target) {
                Boolean visile = enabled.getModelObject();

                configs.setVisible(visile);
                target.add(configsContainer);
            }

        });

        // error message label
        Label noAttributeMessage = new Label("noAttributeMsg", "");
        add(noAttributeMessage);
        
        // the attribute label and dropdown container
        WebMarkupContainer attContainer = new WebMarkupContainer("attributeContainer");
        configs.add(attContainer);

        // check the attributes and show a dropdown
        List<String> attributes = getAttributesOfType(resource, type);
        attribute = new DropDownChoice<String>("attribute", new PropertyModel<String>(model,
		        "attribute"), attributes);
		attribute.setOutputMarkupId(true);
		attribute.setRequired(true);
		attContainer.add(attribute);
        
        List<String> endAttributes = new ArrayList<String>(attributes);
        endAttributes.add(0, "-");
        endAttribute = new DropDownChoice<String>("endAttribute", new PropertyModel<String>(model,
                "endAttribute"), endAttributes);
        endAttribute.setOutputMarkupId(true);
        endAttribute.setRequired(false);
        attContainer.add(endAttribute);

        // do we show it?
        if(resource instanceof FeatureTypeInfo) { 
            if (attributes.isEmpty()) {
                disableDimension(type, configs, noAttributeMessage);
            } else {
                noAttributeMessage.setVisible(false);
            }
        } else if(resource instanceof CoverageInfo) {
            attContainer.setVisible(false);
            attribute.setRequired(false);
            try {
                GridCoverageReader reader = ((CoverageInfo) resource).getGridCoverageReader(null, null);
                if(Number.class.isAssignableFrom(type)) {
                    String elev = reader.getMetadataValue(GridCoverage2DReader.HAS_ELEVATION_DOMAIN);
                    if(!Boolean.parseBoolean(elev)) {
                        disableDimension(type, configs, noAttributeMessage);
                    }
                } else if(Date.class.isAssignableFrom(type)) {
                    String time = reader.getMetadataValue(GridCoverage2DReader.HAS_TIME_DOMAIN);
                    if(!Boolean.parseBoolean(time)) {
                        disableDimension(type, configs, noAttributeMessage);
                    }
                }
            } catch(IOException e) {
                throw new WicketRuntimeException(e);
            }
        }
        
        // units block
        final WebMarkupContainer unitsContainer = new WebMarkupContainer("unitsContainer");
        configs.add(unitsContainer);
        IModel<String> uModel = new PropertyModel<String>(model, "units");
        units = new TextField<String>("units", uModel);
        unitsContainer.add(units);
        IModel<String> usModel = new PropertyModel<String>(model, "unitSymbol");
        unitSymbol = new TextField<String>("unitSymbol", usModel);
        unitsContainer.add(unitSymbol);        
        // set defaults for elevation if units have never been set
        if ("elevation".equals(id) && uModel.getObject() == null) {
            uModel.setObject(DimensionInfo.ELEVATION_UNITS);
            usModel.setObject(DimensionInfo.ELEVATION_UNIT_SYMBOL);
        }

        // presentation/resolution block
        final WebMarkupContainer resContainer = new WebMarkupContainer("resolutionContainer");
        resContainer.setOutputMarkupId(true);
        configs.add(resContainer);
        final WebMarkupContainer resolutions = new WebMarkupContainer("resolutions");
        resolutions
                .setVisible(model.getObject().getPresentation() == DimensionPresentation.DISCRETE_INTERVAL);
        resolutions.setOutputMarkupId(true);
        resContainer.add(resolutions);
        
        presentationModes = new ArrayList<DimensionPresentation>(Arrays.asList(DimensionPresentation.values()));
        presentation = new DropDownChoice<DimensionPresentation>("presentation",
                new PropertyModel<DimensionPresentation>(model, "presentation"),
                presentationModes, new PresentationModeRenderer());
        configs.add(presentation);
        presentation.setRequired(true);
        presentation.add(new AjaxFormComponentUpdatingBehavior("change") {

            @Override
            protected void onUpdate(AjaxRequestTarget target) {
                boolean visible = presentation.getModelObject() == DimensionPresentation.DISCRETE_INTERVAL;
                resolutions.setVisible(visible);
                target.add(resContainer);
            }

        });

        IModel<BigDecimal> rmodel = new PropertyModel<BigDecimal>(model, "resolution");
        resTime = new PeriodEditor("resTime", rmodel);
        resolutions.add(resTime);
        resElevation = new TextField<BigDecimal>("resElevation", rmodel);
        resolutions.add(resElevation);
        time = Date.class.isAssignableFrom(type);
        if(time) {
            resElevation.setVisible(false);
            resTime.setRequired(true);
            unitsContainer.setVisible(false);
        } else {
            resTime.setVisible(false);
            resElevation.setRequired(true);
        }
        
        //default value block
        DimensionDefaultValueSetting defValueSetting = model.getObject().getDefaultValue();
        if (defValueSetting == null){
        	defValueSetting = new DimensionDefaultValueSetting();
        	model.getObject().setDefaultValue(defValueSetting);
        }
        final WebMarkupContainer defValueContainer = new WebMarkupContainer("defaultValueContainer");
        defValueContainer.setOutputMarkupId(true);
        configs.add(defValueContainer);
        final WebMarkupContainer referenceValueContainer = new WebMarkupContainer("referenceValueContainer");
        referenceValueContainer.setOutputMarkupId(true);               
        referenceValueContainer.setVisible((defValueSetting.getStrategyType() == Strategy.FIXED) || (defValueSetting.getStrategyType() == Strategy.NEAREST));          
        defValueContainer.add(referenceValueContainer);
        
        defaultValueStrategies = new ArrayList<DimensionDefaultValueSetting.Strategy>(Arrays.asList(DimensionDefaultValueSetting.Strategy.values()));
        IModel<DimensionDefaultValueSetting.Strategy> strategyModel =  new PropertyModel<DimensionDefaultValueSetting.Strategy>(model.getObject().getDefaultValue(), "strategy");
        defaultValueStrategy = new DropDownChoice<DimensionDefaultValueSetting.Strategy>("strategy",
               strategyModel, defaultValueStrategies, new DefaultValueStrategyRenderer());
        configs.add(defaultValueStrategy);
        defaultValueStrategy.add(new AjaxFormComponentUpdatingBehavior("change") {

            @Override
            protected void onUpdate(AjaxRequestTarget target) {
                boolean visible = (defaultValueStrategy.getModelObject() == Strategy.FIXED) || (defaultValueStrategy.getModelObject() == Strategy.NEAREST);
                referenceValueContainer.setVisible(visible);
                target.add(defValueContainer);
            }

        });
        defValueContainer.add(defaultValueStrategy);
        
        final Label refValueValidationMessage = new Label("refValueValidationMsg", "");
        refValueValidationMessage.setVisible(false);
        
        IModel<String> refValueModel = new PropertyModel<String>(model.getObject().getDefaultValue(), "referenceValue");        
        referenceValue = new TextField<String>("referenceValue", refValueModel);
        referenceValue.add(new AjaxFormComponentUpdatingBehavior("change") {
            
            protected void onUpdate(AjaxRequestTarget target) {
                refValueValidationMessage.setDefaultModelObject(null);
                refValueValidationMessage.setVisible(false);
                target.add(referenceValueContainer);
            }

            @Override
            protected void onError(AjaxRequestTarget target, RuntimeException e) {
                super.onError(target, e);               
                if (referenceValue.hasErrorMessage()){
                    refValueValidationMessage.setDefaultModelObject(referenceValue.getFeedbackMessages().first());
                    refValueValidationMessage.setVisible(true);
                }                
                target.add(referenceValueContainer);
            }
        });
        referenceValue.add(new ReferenceValueValidator(id, strategyModel));
              
        referenceValueContainer.add(referenceValue);
        referenceValueContainer.add(refValueValidationMessage);
        
        // set "current" for reference value if dimension is time, strategy is NEAREST and value has never been set
        if ("time".equals(id) && refValueModel.getObject() == null && strategyModel.getObject() == Strategy.NEAREST) {
            refValueModel.setObject(DimensionDefaultValueSetting.TIME_CURRENT);            
        }
    }
    
    /**
     * Allows to remove presentation modes from the editor. If only a single presentation mode
     * is left the editor will setup in non enabled mode and will return that fixed value
     * @param mode
     */
    public void disablePresentationMode(DimensionPresentation mode) {
        presentationModes.remove(mode);
        if(presentationModes.size() <= 1) {
            presentation.setModelObject(presentationModes.get(0));
            presentation.setEnabled(false);
        }
    }

    private void disableDimension(Class<?> type, final WebMarkupContainer configs,
            Label noAttributeMessage) {
        // no attributes of the required type, no party
        enabled.setEnabled(false);
        enabled.setModelObject(false);
        configs.setVisible(false);
        ParamResourceModel typeName = new ParamResourceModel("AttributeType."
                + type.getSimpleName(), null);
        ParamResourceModel error = new ParamResourceModel("missingAttribute", this, typeName
                .getString());
        noAttributeMessage.setDefaultModelObject(error.getString());
    }

    @Override
    public boolean processChildren() {
        return true;
    }

    public void convertInput() {
        //Keep the original attributes
        if (!enabled.getModelObject()) {
            setConvertedInput(new DimensionInfoImpl());
        } else {
            //To keep the original values for attributes not editable in UI:
            DimensionInfoImpl info = new DimensionInfoImpl(this.getModelObject());
            
            info.setEnabled(true);
            attribute.processInput();
            endAttribute.processInput();
            info.setAttribute(attribute.getModelObject());
            String endAttributeValue = endAttribute.getModelObject();
            if ("-".equals(endAttributeValue)) {
                endAttributeValue = null;
            }
            info.setEndAttribute(endAttributeValue);
            units.processInput();
            String unitsValue = units.getModelObject();
            if ("time".equals(this.getId())) { // only one value is allowed for time units
                unitsValue = DimensionInfo.TIME_UNITS;
            } else if (unitsValue == null) { // allow blank units for any other dimension
                unitsValue = "";
            }
            info.setUnits(unitsValue);
            unitSymbol.processInput();
            info.setUnitSymbol(unitSymbol.getModelObject());
            info.setPresentation(presentation.getModelObject());
            if (info.getPresentation() == DimensionPresentation.DISCRETE_INTERVAL) {
                if(time) {
                    resTime.processInput();
                    info.setResolution(resTime.getModelObject());
                } else {
                    resElevation.processInput();
                    info.setResolution(resElevation.getModelObject());
                }
            }
            DimensionDefaultValueSetting defValueSetting = new DimensionDefaultValueSetting();
            defaultValueStrategy.processInput();            
            defValueSetting.setStrategyType(defaultValueStrategy.getModelObject());
            if (defValueSetting.getStrategyType() == Strategy.FIXED || defValueSetting.getStrategyType() == Strategy.NEAREST){
                referenceValue.processInput();
                if (referenceValue.hasErrorMessage()){
                    System.out.println("About to accept erroneous value "+referenceValue.getModelObject());
                }
                defValueSetting.setReferenceValue(referenceValue.getModelObject());
            }
            if (defValueSetting.getStrategyType() != Strategy.BUILTIN){
                info.setDefaultValue(defValueSetting);                
            }
            else {
                info.setDefaultValue(null);
            }
            setConvertedInput(info);
        }
    };
    
    

    /**
     * Returns all attributes conforming to the specified type
     * 
     * @param resource
     * @param type
     * @return
     */
    List<String> getAttributesOfType(ResourceInfo resource, Class<?> type) {
        List<String> result = new ArrayList<String>();

        if (resource instanceof FeatureTypeInfo) {
            try {
                FeatureTypeInfo ft = (FeatureTypeInfo) resource;
                for (PropertyDescriptor pd : ft.getFeatureType()
                        .getDescriptors()) {
                    if (type.isAssignableFrom(pd.getType().getBinding())) {
                        result.add(pd.getName().getLocalPart());
                    }
                }
            } catch (IOException e) {
                throw new WicketRuntimeException(e);
            }
        }

        return result;
    }

    /**
     * Renders a presentation mode into a human readable form
     * 
     * @author Alessio
     */
    public class PresentationModeRenderer extends ChoiceRenderer<DimensionPresentation> {

        public PresentationModeRenderer() {
            super();
        }

        public Object getDisplayValue(DimensionPresentation object) {
            return new ParamResourceModel(object.name(), DimensionEditor.this).getString();
        }

        public String getIdValue(DimensionPresentation object, int index) {
            return String.valueOf(object.ordinal());
        }
    }
    
    /**
     * Renders a default value strategy into a human readable form
     * 
     * @author Ilkka Rinne / Spatineo Inc for the Finnish Meteorological Institute
     */
    public class DefaultValueStrategyRenderer extends ChoiceRenderer<DimensionDefaultValueSetting.Strategy> {

        public DefaultValueStrategyRenderer() {
            super();
        }

        public Object getDisplayValue(DimensionDefaultValueSetting.Strategy object) {
            return new ParamResourceModel(object.name(), DimensionEditor.this).getString();
        }

        public String getIdValue(DimensionDefaultValueSetting.Strategy object, int index) {
            return String.valueOf(object.ordinal());
        }
    }
    
    /**
     * Validator for dimension default value reference values.
     * 
     * @author Ilkka Rinne / Spatineo Inc for the Finnish Meteorological Institute
     *
     */
    public class ReferenceValueValidator implements IValidator<String> {
        String dimension;
        IModel<DimensionDefaultValueSetting.Strategy> strategyModel;
        
        public ReferenceValueValidator(String dimensionId, IModel<DimensionDefaultValueSetting.Strategy> strategyModel){
            this.dimension = dimensionId;
            this.strategyModel = strategyModel;
        }
        
        @Override
        public void validate(IValidatable<String> value) {
            String stringValue = value.getValue();
            if ( ((strategyModel.getObject() == Strategy.FIXED) || (strategyModel.getObject() == Strategy.NEAREST)) && stringValue == null){
                value.error(new ValidationError("emptyReferenceValue").addKey("emptyReferenceValue"));
            } else if (dimension.equals("time")) {
                if(!isValidTimeReference(stringValue, strategyModel.getObject())) {
                    String messageKey = strategyModel.getObject() == Strategy.NEAREST ?  "invalidNearestTimeReferenceValue" : "invalidTimeReferenceValue";
                    value.error(new ValidationError(messageKey).addKey(messageKey));
                }
                
            } else if (dimension.equals("elevation")) {
                if(!isValidElevationReference(stringValue)) {
                    value.error(new ValidationError("invalidElevationReferenceValue")
                            .addKey("invalidElevationReferenceValue"));
                }
            }
        }

        private boolean isValidElevationReference(String stringValue) {
            try {
                ElevationKvpParser parser = GeoServerExtensions.bean(ElevationKvpParser.class);
                List values = (List) parser.parse(stringValue);
                // the KVP parser accepts also lists of values, we want a single one 
                return values.size() == 1;
            } catch (Exception e) {
                if(LOGGER.isLoggable(Level.FINER)) {
                    LOGGER.log(Level.FINER, "Invalid elevation value " + stringValue, e);
                }
                return false;
            }
        }

        private boolean isValidTimeReference(String stringValue, Strategy strategy) {
            try {
                TimeParser parser = new TimeParser();
                List values = (List) parser.parse(stringValue);
                // the KVP parser accepts also lists of values, we want a single one
                if(strategy == Strategy.FIXED) {
                    // point or range, but just one
                    return values.size() == 1;
                } else if(strategy == Strategy.NEAREST) {
                    // only point value, no ranges allowed
                    return values.size() == 1 && !(values.get(0) instanceof Range);
                } else {
                    // nope, we cannot have a reference value if the strategy is 
                    // not fixed or nearest
                    return false;
                }
            } catch (Exception e) {
                if(LOGGER.isLoggable(Level.FINER)) {
                    LOGGER.log(Level.FINER, "Invalid time value " + stringValue, e);
                }
                return false;
            }
        }
    }
    
}
