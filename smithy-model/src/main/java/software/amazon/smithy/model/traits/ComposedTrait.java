/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package software.amazon.smithy.model.traits;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.node.ObjectNode;
import software.amazon.smithy.model.node.StringNode;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.utils.MapUtils;
import software.amazon.smithy.utils.ToSmithyBuilder;

/**
 * Marks a trait definition as a composition of other traits.
 *
 * <p>When a composed trait is applied to a shape, the traits on the composed
 * trait's definition are logically expanded onto the target shape. The
 * {@code bindings} member maps composed-trait member names to inner trait
 * member paths (format: "traitName/memberName").
 */
public final class ComposedTrait extends AbstractTrait implements ToSmithyBuilder<ComposedTrait> {
    public static final ShapeId ID = ShapeId.from("smithy.api#composed");

    private final Map<String, String> bindings;

    private ComposedTrait(Builder builder) {
        super(ID, builder.sourceLocation);
        this.bindings = Collections.unmodifiableMap(new LinkedHashMap<>(builder.bindings));
    }

    /**
     * Gets the bindings that map composed trait member names to inner trait
     * member paths.
     *
     * @return the bindings map
     */
    public Map<String, String> getBindings() {
        return bindings;
    }

    /**
     * Gets the binding target for a composed trait member name.
     *
     * @param memberName the member name in the composed trait
     * @return the inner trait path (e.g., "http/uri"), if bound
     */
    public Optional<String> getBinding(String memberName) {
        return Optional.ofNullable(bindings.get(memberName));
    }

    @Override
    protected Node createNode() {
        ObjectNode.Builder node = ObjectNode.builder();
        if (!bindings.isEmpty()) {
            ObjectNode.Builder bindingsNode = ObjectNode.builder();
            bindings.forEach((k, v) -> bindingsNode.withMember(k, v));
            node.withMember("bindings", bindingsNode.build());
        }
        return node.build();
    }

    @Override
    public ComposedTrait.Builder toBuilder() {
        return builder().bindings(bindings).sourceLocation(getSourceLocation());
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder extends AbstractTraitBuilder<ComposedTrait, Builder> {
        private final Map<String, String> bindings = new LinkedHashMap<>();

        public Builder bindings(Map<String, String> bindings) {
            this.bindings.clear();
            this.bindings.putAll(bindings);
            return this;
        }

        public Builder putBinding(String memberName, String targetPath) {
            this.bindings.put(memberName, targetPath);
            return this;
        }

        @Override
        public ComposedTrait build() {
            return new ComposedTrait(this);
        }
    }

    public static final class Provider implements TraitService {
        @Override
        public ShapeId getShapeId() {
            return ID;
        }

        @Override
        public ComposedTrait createTrait(ShapeId target, Node value) {
            ObjectNode node = value.expectObjectNode();
            Builder builder = builder().sourceLocation(value.getSourceLocation());
            node.getObjectMember("bindings").ifPresent(bindingsNode -> {
                bindingsNode.getMembers().forEach((k, v) -> {
                    builder.putBinding(k.getValue(), v.expectStringNode().getValue());
                });
            });
            ComposedTrait result = builder.build();
            result.setNodeCache(value);
            return result;
        }
    }
}
