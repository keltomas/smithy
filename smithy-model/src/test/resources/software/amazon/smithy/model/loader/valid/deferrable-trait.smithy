$version: "2"
namespace smithy.example

/// A meta trait with deferrable members that accept deferred values.
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

// Literal integer values — should pass normal validation.
@alarm(
    metricName: "Latency"
    threshold: 200
    period: 300
    evaluationPeriods: 3
)
operation LiteralValues {}

// Deferred placeholder strings on @deferrable members — should pass.
@alarm(
    metricName: "Latency"
    threshold: "${billing.alarm.threshold}"
    period: "${billing.alarm.period}"
    evaluationPeriods: 3
)
operation DeferredValues {}

// Mix of literal and deferred — should pass.
@alarm(
    metricName: "Latency"
    threshold: 500
    period: "${billing.alarm.period}"
    evaluationPeriods: 3
)
operation MixedValues {}
