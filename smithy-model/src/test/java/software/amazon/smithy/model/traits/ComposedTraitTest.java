/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package software.amazon.smithy.model.traits;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.ComposedTraitIndex;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.node.ObjectNode;
import software.amazon.smithy.model.shapes.ShapeId;

public class ComposedTraitTest {

    @Test
    public void loadsTrait() {
        TraitFactory provider = TraitFactory.createServiceFactory();
        Optional<Trait> trait = provider.createTrait(
                ShapeId.from("smithy.api#composed"),
                ShapeId.from("ns.qux#foo"),
                Node.objectNode());

        assertTrue(trait.isPresent());
        assertThat(trait.get(), instanceOf(ComposedTrait.class));
    }

    @Test
    public void loadsTraitWithBindings() {
        ObjectNode bindingsNode = ObjectNode.builder()
                .withMember("uri", "http/uri")
                .withMember("items", "paginated/items")
                .build();
        ObjectNode value = ObjectNode.builder()
                .withMember("bindings", bindingsNode)
                .build();

        TraitFactory provider = TraitFactory.createServiceFactory();
        Optional<Trait> trait = provider.createTrait(
                ShapeId.from("smithy.api#composed"),
                ShapeId.from("ns.qux#foo"),
                value);

        assertTrue(trait.isPresent());
        ComposedTrait composed = (ComposedTrait) trait.get();
        assertEquals(2, composed.getBindings().size());
        assertEquals(Optional.of("http/uri"), composed.getBinding("uri"));
        assertEquals(Optional.of("paginated/items"), composed.getBinding("items"));
    }

    @Test
    public void roundTrips() {
        ComposedTrait trait = ComposedTrait.builder()
                .putBinding("uri", "http/uri")
                .putBinding("items", "paginated/items")
                .build();

        ObjectNode node = trait.toNode().expectObjectNode();
        ObjectNode bindings = node.expectObjectMember("bindings");
        assertEquals("http/uri", bindings.expectStringMember("uri").getValue());
        assertEquals("paginated/items", bindings.expectStringMember("items").getValue());
    }

    @Test
    public void indexResolvesComposedTraits() {
        Model model = Model.assembler()
                .addImport(ComposedTraitTest.class.getResource("composed-trait-index.smithy"))
                .assemble()
                .unwrap();

        ComposedTraitIndex index = ComposedTraitIndex.of(model);
        ShapeId operationId = ShapeId.from("ns.test#GetThing");

        // The operation has @standardOperation applied, which composes @cacheable and @monitored
        assertTrue(index.hasEffectiveTrait(operationId, ShapeId.from("ns.test#cacheable")));
        assertTrue(index.hasEffectiveTrait(operationId, ShapeId.from("ns.test#monitored")));

        // Verify binding forwarded the ttl value from the composed application
        Node cacheableNode = index.getEffectiveTrait(operationId, ShapeId.from("ns.test#cacheable")).get();
        assertEquals(60, cacheableNode.expectObjectNode().expectNumberMember("ttl").getValue().intValue());

        // Verify binding forwarded the metric value to metricName
        Node monitoredNode = index.getEffectiveTrait(operationId, ShapeId.from("ns.test#monitored")).get();
        assertEquals("GetThingLatency",
                monitoredNode.expectObjectNode().expectStringMember("metricName").getValue());
    }
}
