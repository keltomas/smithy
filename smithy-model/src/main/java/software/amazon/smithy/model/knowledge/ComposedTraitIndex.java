/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package software.amazon.smithy.model.knowledge;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.node.ObjectNode;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.shapes.ToShapeId;
import software.amazon.smithy.model.traits.ComposedTrait;
import software.amazon.smithy.model.traits.Trait;
import software.amazon.smithy.model.traits.TraitDefinition;

/**
 * Index that resolves composed traits into their effective inner traits.
 *
 * <p>When a shape has a composed trait applied, this index can resolve
 * the effective traits that result from the composition, including
 * binding forwarding.
 */
public final class ComposedTraitIndex implements KnowledgeIndex {

    private final Model model;
    private final Map<ShapeId, Map<ShapeId, Node>> resolvedTraits = new HashMap<>();

    public ComposedTraitIndex(Model model) {
        this.model = model;
        compute();
    }

    public static ComposedTraitIndex of(Model model) {
        return model.getKnowledge(ComposedTraitIndex.class, ComposedTraitIndex::new);
    }

    /**
     * Gets the effective traits for a shape, including traits resolved from compositions.
     *
     * @param shape the shape to get effective traits for
     * @return map of trait shape ID to resolved trait node value
     */
    public Map<ShapeId, Node> getEffectiveTraits(ToShapeId shape) {
        return resolvedTraits.getOrDefault(shape.toShapeId(), Collections.emptyMap());
    }

    /**
     * Checks if a shape has an effective trait (either directly or via composition).
     *
     * @param shape the shape to check
     * @param traitId the trait to look for
     * @return true if the shape has the trait directly or via composition
     */
    public boolean hasEffectiveTrait(ToShapeId shape, ShapeId traitId) {
        return getEffectiveTraits(shape).containsKey(traitId);
    }

    /**
     * Gets the resolved node value for an effective trait on a shape.
     *
     * @param shape the shape to check
     * @param traitId the trait to look for
     * @return the resolved trait node, if present
     */
    public Optional<Node> getEffectiveTrait(ToShapeId shape, ShapeId traitId) {
        return Optional.ofNullable(getEffectiveTraits(shape).get(traitId));
    }

    private void compute() {
        for (Shape shape : model.toSet()) {
            Map<ShapeId, Node> effective = new LinkedHashMap<>();
            for (Trait trait : shape.getAllTraits().values()) {
                model.getShape(trait.toShapeId()).ifPresent(traitDef -> {
                    if (traitDef.hasTrait(ComposedTrait.class)) {
                        Map<ShapeId, Node> expanded = expandComposition(
                                traitDef, trait.toNode(), traitDef.expectTrait(ComposedTrait.class));
                        effective.putAll(expanded);
                    }
                });
            }
            if (!effective.isEmpty()) {
                resolvedTraits.put(shape.getId(), Collections.unmodifiableMap(effective));
            }
        }
    }

    private Map<ShapeId, Node> expandComposition(Shape traitDef, Node appliedValue, ComposedTrait composed) {
        Map<ShapeId, Node> result = new LinkedHashMap<>();
        ObjectNode appliedObject = appliedValue.isObjectNode()
                ? appliedValue.expectObjectNode()
                : ObjectNode.builder().build();
        Map<String, String> bindings = composed.getBindings();

        // Collect inner traits from the trait definition (excluding @trait and @composed themselves)
        for (Trait innerTrait : traitDef.getAllTraits().values()) {
            ShapeId innerTraitId = innerTrait.toShapeId();
            if (innerTraitId.equals(TraitDefinition.ID) || innerTraitId.equals(ComposedTrait.ID)) {
                continue;
            }

            // Start with the inner trait's value as defined on the composition
            ObjectNode innerNode = innerTrait.toNode().isObjectNode()
                    ? innerTrait.toNode().expectObjectNode()
                    : ObjectNode.builder().build();

            // Apply bindings: forward composed-trait member values to inner trait members
            ObjectNode.Builder resolved = innerNode.toBuilder();
            for (Map.Entry<String, String> binding : bindings.entrySet()) {
                String composedMember = binding.getKey();
                String targetPath = binding.getValue();

                // Parse "traitName/memberName"
                String[] parts = targetPath.split("/", 2);
                if (parts.length != 2) {
                    continue;
                }
                String targetTraitName = parts[0];
                String targetMemberName = parts[1];

                // Check if this binding targets this inner trait
                if (innerTraitId.getName().equals(targetTraitName)) {
                    appliedObject.getMember(composedMember).ifPresent(value -> {
                        resolved.withMember(targetMemberName, value);
                    });
                }
            }

            result.put(innerTraitId, resolved.build());
        }

        return result;
    }
}
