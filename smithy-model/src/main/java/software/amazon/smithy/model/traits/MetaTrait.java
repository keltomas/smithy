/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package software.amazon.smithy.model.traits;

import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.node.ObjectNode;
import software.amazon.smithy.model.shapes.ShapeId;

/**
 * Marks a trait as build-time tooling metadata. Traits marked {@code @meta}
 * are not processed by client codegen, SDKs, JSON Schema, or OpenAPI
 * converters. They exist solely for build plugins and deployment tooling.
 */
public final class MetaTrait extends AnnotationTrait {
    public static final ShapeId ID = ShapeId.from("smithy.api#meta");

    public MetaTrait(ObjectNode node) {
        super(ID, node);
    }

    public MetaTrait() {
        this(Node.objectNode());
    }

    public static final class Provider extends AnnotationTrait.Provider<MetaTrait> {
        public Provider() {
            super(ID, MetaTrait::new);
        }
    }
}
