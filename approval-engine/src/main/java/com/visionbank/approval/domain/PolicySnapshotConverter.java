package com.visionbank.approval.domain;

import tools.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class PolicySnapshotConverter implements AttributeConverter<PolicySnapshot, String> {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(PolicySnapshot attribute) {
        try {
            return attribute == null ? null : MAPPER.writeValueAsString(attribute);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize PolicySnapshot", e);
        }
    }

    @Override
    public PolicySnapshot convertToEntityAttribute(String dbData) {
        try {
            return dbData == null ? null : MAPPER.readValue(dbData, PolicySnapshot.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize PolicySnapshot", e);
        }
    }
}
