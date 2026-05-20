# Trait Composition (Composed Traits)

* **Authors**: keltomas
* **Created**: 2026-05-14
* **Last updated**: 2026-05-19

## Abstract

This proposal introduces a `@composed` meta-trait that allows higher-level
traits to encapsulate a set of lower-level traits. A binding block wires
composed-trait members to inner trait members.

## Motivation

As Smithy ecosystems grow, teams accumulate trait patterns that are repeatedly
applied together. For example, a "standard read operation" might always require
`@readonly`, `@http(method: "GET", ...)`, `@paginated`, and a custom
`@authPolicy` trait. Today, every operation must apply all four individually.

Problems:

1. **Repetition** — the same trait cluster is copied across dozens of operations
2. **Drift** — forgetting one trait from the cluster produces subtle bugs
3. **Coupling** — consumers see implementation details that should be abstracted
4. **Evolution** — adding a new trait to the standard requires updating every use

## Relationship to `@mixin`

Smithy already supports trait composition via operation mixins:

```smithy
@mixin
@readonly
@paginated(inputToken: "nextToken", outputToken: "nextToken", pageSize: "maxResults")
operation StandardListOperation {}

@http(method: "GET", uri: "/things")
operation ListThings with [StandardListOperation] {
    input := { nextToken: String, maxResults: Integer }
    output := { things: ThingList, nextToken: String }
}
```

This works today for **fixed** trait values. After mixin resolution,
`ListThings` has `@readonly` and `@paginated` as real traits.

### Why mixins are insufficient

Mixins cannot solve the **parameterized** case:

```smithy
@mixin
@readonly
@http(method: "GET", uri: ???)  // What goes here? Different per operation.
operation ReadMixin {}
```

With mixins, the team must:
1. Apply the mixin for fixed traits (`@readonly`, `@paginated`)
2. Separately apply variable traits (`@http`) per operation
3. Hope nobody forgets step 2 — there's no enforcement

| Gap | Impact |
|-----|--------|
| No variable values per use site | Each operation must manually apply `@http` with its own `uri` |
| No enforcement of required parts | Forgetting `@http` produces no error — just a broken operation |
| No semantic identity for the pattern | Can't select "all operations using our standard list pattern" |
| Split application | Pattern is two steps (mixin + manual traits) not one atomic step |

### What `@composed` adds

`@composed` extends mixins to parameterized composition:

```smithy
@listOperation(uri: "/things", items: "things")
operation ListThings { ... }
```

One application, variable values enforced, single selectable identity.

### When to use each

| Scenario | Use |
|----------|-----|
| All traits have fixed values | `@mixin` — simpler, works today |
| Some trait members vary per use site | `@composed` — enforces and forwards variable values |
| Need to query "which shapes use this pattern?" | `@composed` — selectable via `[trait|listOperation]` |
| No enforcement needed, just convenience | `@mixin` — less machinery |

## Proposal

### The `@composed` trait

```smithy
@trait(selector: "structure[trait|trait]")
structure composed {
    /// Maps composed trait member names to inner trait member paths.
    /// Format: { "<member>": "<innerTrait>/<innerMember>" }
    bindings: ComposedBindings
}

map ComposedBindings {
    key: String
    value: String
}
```

### Defining a composed trait

```smithy
@composed(
    bindings: {
        uri: "http/uri"
        items: "paginated/items"
    }
)
@trait(selector: "operation")
@readonly
@http(method: "GET")
@paginated(inputToken: "nextToken", outputToken: "nextToken", pageSize: "maxResults")
structure listOperation {
    @required uri: String
    @required items: String
}
```

### Applying a composed trait

```smithy
@listOperation(uri: "/things", items: "things")
operation ListThings { ... }
```

## Core Design Question: Expansion Semantics

The central question is: **after model loading, does `ListThings` have
`@http`, `@readonly`, and `@paginated` as actual traits?**

This determines backward compatibility with the entire Smithy ecosystem.

### Option A: Full expansion (recommended)

**Inner traits are expanded onto the target shape during model loading.**

After expansion, `shape.getAllTraits()` returns BOTH `@listOperation` AND
`@readonly`, `@http(method: "GET", uri: "/things")`, `@paginated(...)`.

```java
// Both work:
shape.getTrait(ListOperationTrait.class)  // ✅ the composed trait
shape.getTrait(HttpTrait.class)           // ✅ the expanded inner trait
shape.getTrait(ReadonlyTrait.class)       // ✅ the expanded inner trait
```

**Why this is recommended:**
- `shape.getTrait(HttpTrait.class)` works unchanged — zero migration for existing code
- Selectors like `[trait|readonly]` match without modification
- Validators, codegen, and plugins all work on inner traits as before
- The composed trait remains as metadata indicating the source of the expansion

**Trade-off:**
- "Encapsulation" is a model-authoring concern, not a runtime concern. After
  loading, the model is fully resolved — consumers see everything.
- This mirrors how `@mixin` works: members are copied to the target, and the
  mixin itself remains as a marker (`shape.getMixins()` returns the sources).

**Serialization:** Only `@listOperation(uri: "/things", items: "things")` is
written to the JSON AST. Inner traits are derived during loading. The
composed trait's presence signals "re-expand on load." `Shape.getIntroducedTraits()`
returns only what's in the model file; `Shape.getAllTraits()` includes expanded.

### Option B: Pure sugar — composed trait is removed

The `@composed` trait is desugared completely. After loading, `ListThings` has
only `@readonly`, `@http`, `@paginated` — no record of `@listOperation`.

**Why this was rejected:**
- Loses the abstraction entirely at runtime
- Can't query "which shapes use our standard pattern?"
- Can't evolve the composed definition without re-running the desugaring
- Model serialization can't reconstruct the original intent

### Option C: Layered visibility (no expansion)

Inner traits exist only in a `ComposedTraitIndex`, not on the shape.
`shape.getTrait(HttpTrait.class)` returns empty.

**Why this was rejected (per Kevin Stich's feedback):**
- "None of the existing call sites for getting trait contents work when a
  trait's value is composed"
- "It's not feasible to route that all through ComposedTraitIndex without a
  full MV of the libraries which we aren't planning to do"
- `shape.expectTrait(HttpTrait.class)` MUST return a valid instance with all
  fields populated

## Implementation: Model-Loading Expansion

Based on feedback from the Smithy team, expansion must happen during model
loading, similar to `@mixin` resolution.

### Insertion point

In `LoadOperationProcessor.buildModel()`:

```java
Model buildModel() {
    resolveForwardReferences();
    traitMap.applyTraitsToNonMixinsInShapeMap(shapeMap);  // traits resolved
    expandComposedTraits(shapeMap, traitMap);              // NEW
    shapeMap.buildShapesAndClaimMixinTraits(...);          // shapes finalized
    return modelBuilder.build();
}
```

At this point, all trait definitions are loaded (we can identify `@composed`
traits), traits are resolved to instances on shape builders (we can read inner
traits from definitions), and shapes aren't finalized yet (we can add traits
to builders).

### Expansion algorithm

```
for each shape builder with a composed trait applied:
    1. look up the composed trait's definition shape
    2. read inner traits from the definition (excluding @trait, @composed)
    3. for each inner trait:
        a. start with the inner trait's node value from the definition
        b. for each binding that targets this inner trait:
            - read the corresponding member value from the composed application
            - merge it into the inner trait node
        c. call TraitFactory.createTrait() to produce a typed instance
        d. apply it to the target shape builder (lower precedence than direct)
    4. keep the composed trait on the shape as metadata
```

### Precedence

Direct traits override composed-expanded traits:

```smithy
@listOperation(uri: "/things", items: "things")
@paginated(maxResults: "limit")  // direct — overrides composed @paginated
operation GetThing { ... }
```

After expansion:
- `@paginated` = the direct application (wins)
- `@http` = expanded from composition
- `@readonly` = expanded from composition
- `@listOperation` = the composed trait (kept as metadata)

### Selector validation for inner traits on definitions

Inner traits on the composed definition (like `@http` on a structure) would
normally fail `TraitTargetValidator` because `@http` has `selector: "operation"`.

Solution: `TraitTargetValidator` skips selector validation for traits applied
to shapes that have `@composed`. This mirrors how trait-on-mixin validation
works — the traits are validated on the final target after expansion, not on
the definition.

### Partial inner traits on definitions

A composed definition may have `@http(method: "GET")` without `uri` — the
`uri` comes from bindings at application time. This means `TraitValueValidator`
would flag a missing required field on the definition.

Solution: Skip `TraitValueValidator` for inner traits on `@composed`
definitions. They're templates — validated only after expansion when all
bindings are applied.

## Backward compatibility analysis

| Existing code pattern | Works with Option A? |
|-----------------------|---------------------|
| `shape.getTrait(HttpTrait.class)` | ✅ returns expanded trait |
| `shape.hasTrait(ReadonlyTrait.class)` | ✅ true after expansion |
| `shape.getAllTraits()` | ✅ includes both composed + expanded |
| `shape.getIntroducedTraits()` | Returns only `@listOperation` (not expanded) |
| Selector `[trait|readonly]` | ✅ matches after expansion |
| `TraitTargetValidator` | ✅ validates expanded traits on target |
| Model serialization | Writes only `@listOperation`; re-expands on load |
| `smithy-diff` | Compares serialized form (composed only) |

## Bindings

The `bindings` member of `@composed` declares how member values flow to inner
traits:

```smithy
@composed(
    bindings: {
        uri: "http/uri"
        items: "paginated/items"
    }
)
```

Each entry: `<composed member name>` → `<innerTrait>/<innerMember>`.
Validated at load time — target path must exist in the corresponding inner trait.

## Interaction with `@meta` + `@deferrable`

These features are independent but complementary. A composed trait can include
`@meta` traits, and bindings can forward `deferred()` values:

```smithy
@composed(
    bindings: {
        uri: "http/uri"
        threshold: "alarm/threshold"
    }
)
@trait(selector: "operation")
@readonly
@http(method: "GET")
@alarm(metricName: "default")
structure monitoredReadOperation {
    @required uri: String
    @deferrable threshold: Integer
}
```

## Open questions

1. **Should expanded traits be distinguishable from direct traits?** Could add
   a `ComposedTraitIndex` that records provenance ("this @http came from
   @listOperation") without affecting `getTrait()` behavior.

2. **Recursive composition** — if a composed trait includes another composed
   trait, expansion order matters. Propose: depth-first, same as mixin ordering.

3. **How does this interact with trait conflict resolution?** If two composed
   traits both expand to `@http`, which wins? Propose: error — same as applying
   the same trait twice directly. The error message should point to both
   composed traits so the user can resolve the conflict.

## Design rationale — anticipated concerns

### "This adds too much complexity to the trait system"

`@composed` is the minimal extension beyond what mixins provide. The model
surface addition is one trait with one member (`bindings`). There are no new
shape types, no new node types, no new IDL syntax. The complexity is in the
model loader — which already handles mixin expansion using the same pattern.

### "Serialization: what if the composed definition changes?"

If a composed trait definition adds a new inner trait, models using it will
gain that trait on next load. This is **identical** to how mixin changes work:
if a mixin adds a member, all users gain it. The mitigation is the same —
composed trait definitions are versioned artifacts subject to
`breakingChanges` rules.

### "The TraitTargetValidator carve-out weakens safety"

Inner traits on `@composed` definitions are not validated against the
definition's selector — only against the final target after expansion. This
mirrors how traits on mixin shapes work today: `@mixin` shapes can carry
traits that only make sense on the final target. The carve-out is scoped to
shapes that have `@composed` — it doesn't weaken validation for any other
shapes.

### "Why not just use mixins + a custom validator for enforcement?"

A custom validator could check "if this mixin is used, `@http` must also be
present." But this approach: (1) requires a separate validator per pattern,
(2) can't forward values — only check presence, (3) splits the pattern across
multiple files (mixin + validator + documentation), and (4) isn't discoverable
via selectors. `@composed` provides all of this as a single, self-contained
declaration.

## Alternatives explored

### 1. ComposedTraitIndex only (no expansion)

Rejected per Smithy team feedback: "not feasible to route all existing call
sites through ComposedTraitIndex."

### 2. Per-member `@forwards` annotation

Rejected: clutters member definitions, harder to read than a single bindings block.

### 3. Implicit name-matching

Rejected: name collisions between inner traits are unresolvable.

### 4. Mixin-based composition

Mixins handle fixed trait composition today. Rejected for parameterized
composition because: (1) no mechanism for per-site values forwarded to inner
trait members, (2) no enforcement that variable traits are applied, (3) no
single semantic identity for the pattern. See "Relationship to @mixin" section.
