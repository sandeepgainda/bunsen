package com.cerner.bunsen.definitions;

import ca.uhn.fhir.context.BaseRuntimeChildDefinition;
import ca.uhn.fhir.context.BaseRuntimeElementCompositeDefinition;
import ca.uhn.fhir.context.BaseRuntimeElementDefinition;
import ca.uhn.fhir.context.RuntimeElemContainedResourceList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.hl7.fhir.instance.model.api.IAnyResource;
import org.hl7.fhir.instance.model.api.IBase;
import org.hl7.fhir.instance.model.api.IBaseExtension;
import org.hl7.fhir.instance.model.api.IBaseHasExtensions;

public abstract class HapiCompositeConverter<T> extends HapiConverter<T> {

  private final String elementType;

  private final List<StructureField<HapiConverter<T>>> children;

  private final T structType;

  private final String extensionUrl;

  private final FhirConversionSupport fhirSupport;

  protected abstract Object getChild(Object composite, int index);

  protected abstract Object createComposite(Object[] children);

  protected abstract boolean isMultiValued(T schemaType);

  /**
   * Field setter that does nothing for synthetic or unsupported field types.
   */
  private static class NoOpFieldSetter implements HapiFieldSetter,
      HapiObjectConverter {

    @Override
    public void setField(IBase parentObject, BaseRuntimeChildDefinition fieldToSet,
        Object sparkObject) {

    }

    @Override
    public IBase toHapi(Object input) {
      return null;
    }

  }

  private static final HapiFieldSetter NOOP_FIELD_SETTER = new NoOpFieldSetter();

  private class CompositeFieldSetter implements HapiFieldSetter,
      HapiObjectConverter {

    private final List<StructureField<HapiFieldSetter>> children;


    private final BaseRuntimeElementCompositeDefinition compositeDefinition;

    CompositeFieldSetter(BaseRuntimeElementCompositeDefinition compositeDefinition,
        List<StructureField<HapiFieldSetter>> children) {
      this.compositeDefinition = compositeDefinition;
      this.children = children;
    }


    @Override
    public IBase toHapi(Object rowObject) {

      IBase fhirObject = compositeDefinition.newInstance();

      // TODO: interrogate schema to obtain fields in case they are somehow reordered?

      // Rows may be larger than the expected HAPI structure in case they
      // include added columns.
      // if (row.size() < children.size()) {
      //  throw new IllegalStateException("Unexpected row during deserialization "
      //      + row.toString());
      // }

      Iterator<StructureField<HapiFieldSetter>> childIterator = children.iterator();

      for (int fieldIndex = 0; fieldIndex < children.size(); ++fieldIndex) {

        StructureField<HapiFieldSetter> child = childIterator.next();

        // Some children are ignored, for instance when terminating recursive
        // fields.
        if (child == null || child.result() == null) {
          continue;
        }

        Object fieldValue = getChild(rowObject, fieldIndex);

        if (fieldValue != null) {

          if (child.extensionUrl() != null) {

            BaseRuntimeChildDefinition childDefinition =
                compositeDefinition.getChildByName("extension");

            child.result().setField(fhirObject, childDefinition, fieldValue);

          } else {

            String propertyName = child.isChoice()
                ? child.propertyName() + "[x]"
                : child.propertyName();

            BaseRuntimeChildDefinition childDefinition =
                compositeDefinition.getChildByName(propertyName);

            child.result().setField(fhirObject, childDefinition, fieldValue);

          }
        }
      }

      if (extensionUrl != null) {

        ((IBaseExtension) fhirObject).setUrl(extensionUrl);
      }

      return fhirObject;
    }

    @Override
    public void setField(IBase parentObject,
        BaseRuntimeChildDefinition fieldToSet,
        Object sparkObject) {

      IBase fhirObject = toHapi(sparkObject);

      if (extensionUrl != null) {

        fieldToSet.getMutator().addValue(parentObject, fhirObject);

      } else {
        fieldToSet.getMutator().setValue(parentObject, fhirObject);
      }

    }
  }

  protected HapiCompositeConverter(String elementType,
      List<StructureField<HapiConverter<T>>> children,
      T structType,
      FhirConversionSupport fhirSupport,
      String extensionUrl) {

    this.elementType = elementType;
    this.children = children;
    this.structType = structType;
    this.extensionUrl = extensionUrl;
    this.fhirSupport = fhirSupport;
  }

  @Override
  public Object fromHapi(Object input) {

    IBase composite = (IBase) input;

    Object[] values = new Object[children.size()];

    if (composite instanceof IAnyResource) {

      values[0] = ((IAnyResource) composite).getIdElement().getValueAsString();
    }

    Map<String,List> properties = fhirSupport.compositeValues(composite);

    Iterator<StructureField<HapiConverter<T>>> schemaIterator
        = children.iterator();

    // Co-iterate with an index so we place the correct values into the corresponding locations.
    for (int valueIndex = 0; valueIndex < children.size(); ++valueIndex) {

      StructureField<HapiConverter<T>> schemaEntry = schemaIterator.next();

      String propertyName = schemaEntry.propertyName();

      // Append the [x] suffix for choice properties.
      if (schemaEntry.isChoice()) {
        propertyName = propertyName + "[x]";
      }

      HapiConverter<T> converter = schemaEntry.result();

      List propertyValues = properties.get(propertyName);

      if (propertyValues != null && !propertyValues.isEmpty()) {

        if (isMultiValued(converter.getDataType())) {

          values[valueIndex] = schemaEntry.result().fromHapi(propertyValues);

        } else {

          values[valueIndex] = schemaEntry.result().fromHapi(propertyValues.get(0));
        }
      } else if (converter.extensionUrl() != null) {

        // No corresponding property for the name, so see if it is an extension.
        List<? extends IBaseExtension> extensions =
            ((IBaseHasExtensions) composite).getExtension();

        for (IBaseExtension extension: extensions) {

          if (extension.getUrl().equals(converter.extensionUrl())) {

            values[valueIndex] = schemaEntry.result().fromHapi(extension);
          }
        }
      }
    }

    return createComposite(values);
  }

  public HapiFieldSetter toHapiConverter(BaseRuntimeElementDefinition... elementDefinitions) {

    BaseRuntimeElementDefinition elementDefinition = elementDefinitions[0];

    if (elementDefinition instanceof RuntimeElemContainedResourceList) {
      return NOOP_FIELD_SETTER;
    }

    BaseRuntimeElementCompositeDefinition compositeDefinition =
        (BaseRuntimeElementCompositeDefinition) elementDefinition;

    List<StructureField<HapiFieldSetter>> toHapiChildren = children.stream().map(child -> {

      HapiFieldSetter childConverter;

      // Handle extensions.
      if (child.extensionUrl() != null) {

        BaseRuntimeChildDefinition childDefinition =
            compositeDefinition.getChildByName("extension");

        childConverter = child.result()
            .toHapiConverter(childDefinition.getChildByName("extension"));

      } else {

        String propertyName = child.propertyName();

        // Append the [x] suffix for choice properties.
        if (child.isChoice()) {

          propertyName = propertyName + "[x]";
        }

        BaseRuntimeChildDefinition childDefinition =
            compositeDefinition.getChildByName(propertyName);

        BaseRuntimeElementDefinition[] childElementDefinitions;

        if (child.isChoice()) {

          int childCount = childDefinition.getValidChildNames().size();

          childElementDefinitions = new BaseRuntimeElementDefinition[childCount];

          int index = 0;

          for (String childName: childDefinition.getValidChildNames()) {

            childDefinition.getChildByName(childName);

            childElementDefinitions[index++] = childDefinition.getChildByName(childName);
          }

        } else {

          childElementDefinitions = new BaseRuntimeElementDefinition[] {
              childDefinition.getChildByName(propertyName)
          };
        }

        childConverter = child.result().toHapiConverter(childElementDefinitions);
      }

      return new StructureField<HapiFieldSetter>(child.propertyName(),
          child.fieldName(),
          child.extensionUrl(),
          child.isChoice(),
          childConverter);

    }).collect(Collectors.toList());

    return new CompositeFieldSetter(compositeDefinition, toHapiChildren);
  }

  @Override
  public T getDataType() {
    return structType;
  }

  @Override
  public String extensionUrl() {
    return extensionUrl;
  }

  @Override
  public String getElementType() {
    return elementType;
  }
}