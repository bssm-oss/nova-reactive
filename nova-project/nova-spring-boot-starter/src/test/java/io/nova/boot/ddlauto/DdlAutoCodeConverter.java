package io.nova.boot.ddlauto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class DdlAutoCodeConverter implements AttributeConverter<DdlAutoCode, String> {
    @Override
    public String convertToDatabaseColumn(DdlAutoCode attribute) {
        return attribute == null ? null : attribute.value();
    }

    @Override
    public DdlAutoCode convertToEntityAttribute(String dbData) {
        return dbData == null ? null : new DdlAutoCode(dbData);
    }
}
