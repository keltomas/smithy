/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package software.amazon.smithy.model.traits;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.shapes.ShapeId;

public class DeferrableTraitTest {

    @Test
    public void loadsMetaTrait() {
        TraitFactory provider = TraitFactory.createServiceFactory();
        Optional<Trait> trait = provider.createTrait(
                ShapeId.from("smithy.api#meta"),
                ShapeId.from("ns.qux#foo"),
                Node.objectNode());

        assertTrue(trait.isPresent());
        assertThat(trait.get(), instanceOf(MetaTrait.class));
        assertThat(trait.get().toNode(), equalTo(Node.objectNode()));
    }

    @Test
    public void loadsDeferrableTrait() {
        TraitFactory provider = TraitFactory.createServiceFactory();
        Optional<Trait> trait = provider.createTrait(
                ShapeId.from("smithy.api#deferrable"),
                ShapeId.from("ns.qux#foo"),
                Node.objectNode());

        assertTrue(trait.isPresent());
        assertThat(trait.get(), instanceOf(DeferrableTrait.class));
        assertThat(trait.get().toNode(), equalTo(Node.objectNode()));
    }

    @Test
    public void validPlaceholders() {
        assertTrue(DeferrableTrait.isPlaceholder(Node.from("${foo}")));
        assertTrue(DeferrableTrait.isPlaceholder(Node.from("${alarm.threshold}")));
        assertTrue(DeferrableTrait.isPlaceholder(Node.from("${a}")));
        assertTrue(DeferrableTrait.isPlaceholder(Node.from("${MyStack/Resource/Attr}")));
    }

    @Test
    public void invalidPlaceholders() {
        // Not a string
        assertFalse(DeferrableTrait.isPlaceholder(Node.from(42)));
        assertFalse(DeferrableTrait.isPlaceholder(Node.nullNode()));
        assertFalse(DeferrableTrait.isPlaceholder(Node.objectNode()));

        // String but not placeholder syntax
        assertFalse(DeferrableTrait.isPlaceholder(Node.from("plain string")));
        assertFalse(DeferrableTrait.isPlaceholder(Node.from("")));
        assertFalse(DeferrableTrait.isPlaceholder(Node.from("${}")));
        assertFalse(DeferrableTrait.isPlaceholder(Node.from("prefix${foo}")));
        assertFalse(DeferrableTrait.isPlaceholder(Node.from("${foo}suffix")));
        assertFalse(DeferrableTrait.isPlaceholder(Node.from("prefix${foo}suffix")));
        assertFalse(DeferrableTrait.isPlaceholder(Node.from("$foo}")));
        assertFalse(DeferrableTrait.isPlaceholder(Node.from("{foo}")));
    }
}
