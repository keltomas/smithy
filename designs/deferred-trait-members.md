# Deferred trait member values

* **Authors**: keltomas
* **Created**: 2026-05-13
* **Last updated**: 2026-05-14

## Abstract

This proposal introduces two meta-traits (`@meta` and `@deferrable`) and an
IDL syntactic sugar (`deferred("key")`) to support trait members whose values
are resolved at deploy time rather than model-authoring time.

## Motivation

Smithy traits are used by downstream tools (CDK, Terraform, deployment
frameworks) to generate infrastructure configuration. Some trait member values
are not known at model-authoring time — they depend on the deployment context,
environment, or values produced by other resources.

Today, model authors are forced to provide a valid literal value for every
trait member, even when the actual value will be resolved later. This creates
several problems:

1. **`@required` members with no meaningful default.** A threshold, ARN, or
   account ID may not have a sensible hardcoded value. Authors are forced to
   invent placeholder literals (`0`, `"arn:aws:iam::000000000000:role/fake"`)
   that pollute the model and can be confused with real values.

2. **Constraint traits reject placeholders.** A member with
   `@pattern("^arn:aws:.*")` or `@range(min: 1)` cannot accept an arbitrary
   reference string.

3. **No single source of truth.** Without inline parameter references, the
   mapping between trait members and external parameter keys must live in a
   separate configuration file, creating drift risk.

4. **String-typed members are ambiguous.** If a member targets String, there
   is no way to distinguish a literal string value from a parameter key without
   an explicit syntactic marker.

## Proposal

### The `@meta` trait

```smithy
/// Marks a trait as build-time tooling metadata. Traits marked @meta are
/// not processed by client codegen, SDKs, JSON Schema, or OpenAPI converters.
/// They exist solely for build plugins and deployment tooling.
@trait(selector: "[trait|trait]")
structure meta {}
```

`@meta` signals to the ecosystem:
- **trait-codegen:** skip — don't generate Java/Rust/TS classes
- **Client SDKs:** ignore — not part of the service contract
- **smithy-diff:** different rules — changes aren't client-facing breaking
- **OpenAPI/JSON Schema:** exclude — doesn't affect wire format

This containment is what makes `@deferrable` safe — the type system hole is
invisible to all downstream consumers because they never process `@meta` traits.

### The `@deferrable` trait

```smithy
/// Marks a trait member as accepting a deploy-time deferred value.
/// Valid only on members of traits that have @meta applied.
/// Deferrable members override @required — they may be omitted or
/// provided with a deferred("key") value.
@trait(selector: "[trait|trait][trait|meta] > member")
structure deferrable {}
```

This selector targets members of ANY shape type that is both a trait definition
and marked `@meta` — including structure members, list members, and map members.

### Real-world example: `@cognitoUserPools`

Today, hundreds of services write fake placeholder values:
```smithy
@cognitoUserPools(providerArns: ["REPLACE_WITH_COGNITO_USER_POOL_IMPORT"])
```

With `@deferrable`, the trait can be defined to accept deferred values:
```smithy
@meta
@trait(selector: "service")
structure cognitoUserPools {
    @required
    @deferrable
    providerArns: CognitoUserPoolsProviderArns
}
```

Usage with proper deferred semantics:
```smithy
@cognitoUserPools(providerArns: [deferred("cognito.user.pool.arn")])
```

Individual list elements can also be deferred while others remain literal:
```smithy
@cognitoUserPools(providerArns: [
    "arn:aws:cognito-idp:us-east-1:123456789:userpool/known-pool"
    deferred("dynamic.pool.arn")
])
```

For this to work, the list's member shape must have `@deferrable`:
```smithy
@meta
list CognitoUserPoolsProviderArns {
    @deferrable
    member: String
}
```

### The `deferred("key")` IDL syntax

A built-in function form in the IDL that produces a placeholder value:

```
deferred("billing.alarm.threshold")
```

**Desugars to:** `StringNode("${billing.alarm.threshold}")`

The IDL parser recognizes `deferred` as a keyword in value position and emits
the corresponding `${...}` string. When serializing back to IDL, strings
matching `^\$\{.+\}$` on `@deferrable` members are formatted as
`deferred("...")`.

The `deferred()` function form is unambiguous for all member types — including
String members with `@pattern` or `@length` constraints — because it's a
syntactically distinct value form, not a regular string literal.

### Why this design

The design rests on three principles:

1. **Containment via `@meta`:** The type system hole (accepting a string where
   an integer is expected) is fully contained. Only build plugins that
   explicitly understand `@deferrable` ever process these traits. Codegen, SDKs,
   JSON Schema, and OpenAPI converters skip `@meta` traits entirely.

2. **Separation of concerns:** `@meta` controls visibility ("don't expose to
   clients"). `@deferrable` controls values ("this can be resolved later").
   They are independent — you can have `@meta` without `@deferrable`, but not
   `@deferrable` without `@meta`.

3. **User-provided keys at application site:** The trait author defines WHICH
   members are deferrable. The trait consumer provides THEIR OWN keys via
   `deferred("my.key")`. This keeps trait definitions reusable across
   deployment contexts.

### Full example

```smithy
$version: "2"
namespace smithy.example

@meta
@trait(selector: "operation")
structure alarm {
    @required
    metricName: String

    @required
    @deferrable
    threshold: Integer

    @deferrable
    period: Integer

    evaluationPeriods: Integer
}

// All literal values — fully validated
@alarm(
    metricName: "Latency"
    threshold: 200
    period: 300
    evaluationPeriods: 3
)
operation LiteralValues {}

// Deferred values — user provides their keys
@alarm(
    metricName: "Latency"
    threshold: deferred("billing.alarm.threshold")
    period: deferred("billing.alarm.period")
    evaluationPeriods: 3
)
operation DeferredValues {}

// Mix — threshold is literal, period is deferred
@alarm(
    metricName: "Latency"
    threshold: 500
    period: deferred("billing.alarm.period")
    evaluationPeriods: 3
)
operation MixedValues {}

// Deferred members omitted entirely — also valid
@alarm(metricName: "Latency", evaluationPeriods: 3)
operation OmittedValues {}
```

### Validation behavior

When `NodeValidationVisitor` encounters a member:

1. If the member has `@deferrable`, AND
2. The containing trait has `@meta`, AND
3. The value is a `StringNode` matching `^\$\{.+\}$`

Then all validation is skipped for that member — type checking, constraint
traits, and custom plugins.

If the value does NOT match the `${...}` pattern, normal validation applies.
Literal values on `@deferrable` members are still fully validated.

If a `@deferrable` member is also `@required`, the member may be omitted
without error (deferred resolution satisfies the requirement).

### JSON AST representation

```json
{
    "smithy.example#DeferredValues": {
        "type": "operation",
        "traits": {
            "smithy.example#alarm": {
                "metricName": "Latency",
                "threshold": "${billing.alarm.threshold}",
                "period": "${billing.alarm.period}",
                "evaluationPeriods": 3
            }
        }
    }
}
```

### Build plugin output

A build plugin walks the model, finds `@meta` trait applications with
`@deferrable` members, and emits `deferred-parameters.json` — a self-describing
manifest keyed by the user-provided parameter names:

```json
{
    "billing.alarm.threshold": {
        "type": "integer",
        "constraints": {
            "range": { "min": 1, "max": 1000 }
        }
    },
    "billing.alarm.period": {
        "type": "integer",
        "constraints": {}
    }
}
```

Each entry captures:
- **key** — the user-provided name from `deferred("...")`
- **type** — the target type of the member
- **constraints** — all constraint traits applied to the member (`@range`,
  `@pattern`, `@length`, etc.)

A complementary CDK construct consumes this JSON to create typed parameters
with the correct constraints applied, ensuring deploy-time values satisfy
the same contract Smithy would have enforced at model-validation time.

### Breaking changes

- **Adding `@meta`** to a trait: NOT breaking (tooling ignores it gracefully)
- **Removing `@meta`** from a trait: POTENTIALLY breaking (consumers may start processing it)
- **Adding `@deferrable`** to a member: NOT breaking (widens what's accepted)
- **Removing `@deferrable`** from a member: BREAKING (existing `deferred()` values fail)

## Relationship to existing patterns

### Smithy's OpenAPI `jsonAdd` and CloudFormation substitutions

Smithy already supports deploy-time value resolution in a narrow context.
The [OpenAPI conversion](https://smithy.io/2.0/guides/model-translations/converting-to-openapi.html)
documents two relevant mechanisms:

1. **`jsonAdd`** — adds or replaces values in the generated OpenAPI document
   at JSON pointer locations. Used to inject CloudFormation intrinsic functions
   (`Fn::If`, `Fn::Sub`, `Ref`) into specific paths of the output.

2. **AWS CloudFormation substitutions** — when `smithy-aws-apigateway-openapi`
   is on the classpath, Smithy automatically wraps specific OpenAPI paths in
   `Fn::Sub` if the value uses `${x}` variable syntax. This applies only to
   well-known locations like `authorizerUri`, `credentials`, and
   `connectionId`.

**How our approach differs:**

| Dimension | `jsonAdd` / CFN substitutions | `@meta` + `@deferrable` |
|-----------|-------------------------------|-------------------------|
| Scope | Only OpenAPI plugin output at specific JSON paths | Any custom trait member |
| Abstraction | Post-processes generated artifacts | Declares intent in the model |
| Coupling | Tied to OpenAPI, API Gateway, CloudFormation | Consumer-agnostic |
| Discoverability | Hidden in `smithy-build.json` config | Visible in the model via `@deferrable` |
| Key authoring | Knows the output JSON path | User names their key with `deferred("my.key")` |
| Valid model required? | Yes — model must have valid production values | No — `@deferrable` members can be omitted |

**They are complementary.** Smithy already uses `${}` syntax for deploy-time
substitution in the API Gateway context. Our design extends this convention
from "implicit in specific OpenAPI paths" to "explicit and user-declared via
`deferred("key")`". The `disableCloudFormationSubstitution` config option shows
the Smithy team already acknowledges that `${}` can conflict with literal
values — `deferred()` provides the explicit opt-in that avoids this ambiguity.

## Implementation plan

### Core changes (smithy-model)

| Component | Change |
|-----------|--------|
| `prelude.smithy` | Add `@meta` and `@deferrable` trait definitions |
| `MetaTrait.java` | Annotation trait class |
| `DeferrableTrait.java` | Annotation trait class with `isPlaceholder(Node)` helper |
| `NodeValidationVisitor.java` | Skip validation for `${...}` on `@deferrable` members of `@meta` traits |
| `NullableIndex` or equivalent | `@deferrable` overrides `@required` (member can be omitted) |
| `TraitService` SPI | Register both new traits |

### IDL parser changes (smithy-model)

| Component | Change |
|-----------|--------|
| `IdlTraitParser.java` | Recognize `deferred("...")` in trait value position, emit `StringNode("${...}")` |

### IDL formatter changes (smithy-syntax)

| Component | Change |
|-----------|--------|
| Formatter | Detect `${...}` strings on `@deferrable` members, output as `deferred("...")` |

### Downstream (library)

| Package | Role |
|---------|------|
| Traits package | Define domain-specific `@meta` traits using `@deferrable` members |
| Build plugin | Walk model, extract deferred values, emit `deferred-parameters.json` |
| Example model | Demonstrate usage with `deferred("key")` |

## FAQ

### Why two traits (`@meta` and `@deferrable`) instead of one?

Separation of concerns. `@meta` is about visibility — "don't expose this to
clients." `@deferrable` is about values — "this member can be resolved later."
You might want `@meta` without `@deferrable` (a trait that's purely build-time
but has all-literal values). You can't have `@deferrable` without `@meta`
(enforced by selector).

### Why `deferred("key")` and not `${key}` directly?

Both exist — `deferred("key")` is IDL sugar for the AST value `"${key}"`. The
function form is explicit, self-documenting, and impossible to confuse with a
literal string. Reading `threshold: deferred("billing.alarm.threshold")` makes
intent clear without knowing the `${...}` convention.

### Can I use `deferred()` outside of `@meta` traits?

No. Validation only skips the `${...}` pattern on `@deferrable` members within
`@meta` traits. Using `deferred()` elsewhere would produce a validation error
(type mismatch).

### What if I want a literal value that looks like `${...}`?

Within `@meta` traits on `@deferrable` members, you cannot — `${...}` is
always interpreted as a deferred key. In practice this is not a meaningful
limitation since these traits are build-time metadata, not user-facing strings.

### Does `@meta` affect trait resolution or model loading?

No. `@meta` traits are loaded and resolved identically to any other trait.
They participate in selectors, model queries, and the model graph. `@meta`
is a signal to downstream consumers, not to the Smithy model loader.

### Is `deferred` a reserved keyword?

`deferred` is recognized as a value-producing keyword in the IDL parser, only
in trait value position. It cannot be used as a shape name in the same
namespace without ambiguity. This follows the same pattern as other IDL
keywords. Note that adding `deferred()` requires an IDL version bump since
older parsers would not recognize the keyword.

### What about traits that are partially operational and partially build-time?

`@meta` is all-or-nothing by design. If a trait has members used by codegen
AND members that should be deferrable, split it into two traits: an
operational trait (for codegen-consumed members) and a `@meta` trait (for
deferrable members). This separation is deliberate — it keeps the operational
contract clean and makes explicit which parts are build-time vs runtime.

This mirrors how Smithy already separates concerns: `@http` (wire format) vs
`@aws.apigateway#integration` (deployment config) are distinct traits even
though they relate to the same operation.

### What about `toNode()` — doesn't `${...}` break typed access?

Code that calls `trait.toNode().expectObjectNode().expectNumberMember("threshold")`
on a `@meta` trait with a deferred value would throw. This is correct behavior
— `@meta` traits MUST NOT be processed by code that expects typed values
without first checking `DeferrableTrait.isPlaceholder()`.

This is not a new burden. `@meta` traits are explicitly excluded from codegen,
SDKs, and typed consumers. Only build plugins — which understand deferral —
process them. A build plugin that reads `@meta` trait values is responsible
for handling both literal and deferred cases.

### Why not model deferrable as a union type?

An alternative would be: `@deferrable` members target a union like
`Integer | DeferredString`. This keeps the type system intact without
validation bypass.

Rejected because: (1) Smithy traits don't support union-targeted members
in practice — the Node representation would change from a bare integer to a
tagged union object, breaking all existing trait value patterns. (2) Every
consumer would need to unwrap the union. (3) The value of `@meta` is precisely
that it signals "don't process this" — there's no consumer that needs to
unwrap a union because no typed consumer touches these traits.

## Alternatives explored

### 1. Validation bypass without `@meta` containment

A single `@parameterized` trait on any structure member with `${...}` bypass
in `NodeValidationVisitor`. Rejected because the type system hole is visible
to all downstream consumers — trait-codegen generates typed getters that throw
on unexpected StringNodes, and smithy-diff, JSON Schema, and OpenAPI converters
are all affected.

### 2. Pure library — no core change

`@parameterized` as pure metadata with members always holding valid literals.
A build plugin generates `deferred-parameters.json` mapping members to external keys.
Rejected because it cannot handle `@required` members with no meaningful
default, or members with constraint traits. No way to suppress type-mismatch
errors from library-level code.

### 3. New node type (`ParameterNode`)

A new node alongside `StringNode`, `NumberNode`, etc. with IDL syntax
`parameter("key")`. Rejected due to massive blast radius — every node consumer
(codegen, transforms, selectors, serialization) must handle the new type.

### 4. No sigil — any string on a deferrable member is a key

If the member has the deferrable trait and the value is a string, skip
validation. Rejected because String-typed members with `@pattern`/`@length`
constraints become ambiguous — can't distinguish a literal from a key.

### 5. Separate companion trait for keys

```smithy
@alarm(metricName: "Latency", evaluationPeriods: 3)
@alarmParameters(threshold: "key1", period: "key2")
operation Foo {}
```

Rejected because two traits must be maintained in sync with no compile-time
enforcement. Drift risk between the trait application and its parameter
mapping.

### 6. Deferrable means always-a-key (no literal option)

If a member is deferrable, providing any value makes it a key. Rejected
because you lose the ability to sometimes provide a literal and sometimes
defer per application site. The `deferred()` function form gives
per-application choice.
