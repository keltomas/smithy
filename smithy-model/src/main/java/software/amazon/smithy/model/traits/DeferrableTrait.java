/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package software.amazon.smithy.model.traits;

import java.util.regex.Pattern;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.node.ObjectNode;
import software.amazon.smithy.model.shapes.ShapeId;

/**
 * Marks a trait member as accepting a deploy-time deferred value via
 * {@code deferred("key")} syntax. Valid only on members of traits that
 * have {@code @meta} applied.
 *
 * <p>Deferrable members override {@code @required} — they may be omitted
 * or provided with a {@code deferred("key")} value that desugars to
 * {@code "${key}"} in the AST.
 */
public final class DeferrableTrait extends AnnotationTrait {
    public static final ShapeId ID = ShapeId.from("smithy.api#deferrable");
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("^\\$\\{.+}$");

    public DeferrableTrait(ObjectNode node) {
        super(ID, node);
    }

    public DeferrableTrait() {
        this(Node.objectNode());
    }

    /**
     * Returns true if the given node is a deferred placeholder value
     * (a string matching {@code ${...}}).
     *
     * @param value the node to check
     * @return true if the value is a deferred placeholder reference
     */
    public static boolean isPlaceholder(Node value) {
        return value.isStringNode()
                && PLACEHOLDER_PATTERN.matcher(value.expectStringNode().getValue()).matches();
    }

    public static final class Provider extends AnnotationTrait.Provider<DeferrableTrait> {
        public Provider() {
            super(ID, DeferrableTrait::new);
        }
    }
}
