$version: "2"
namespace ns.test

// Inner traits with broad selectors
@trait(selector: "*")
structure cacheable {
    ttl: Integer
}

@trait(selector: "*")
structure monitored {
    metricName: String
}

// Composed trait that bundles cacheable + monitored
@composed(
    bindings: {
        ttl: "cacheable/ttl"
        metric: "monitored/metricName"
    }
)
@trait(selector: "operation")
@cacheable(ttl: 300)
@monitored(metricName: "default")
structure standardOperation {
    ttl: Integer
    metric: String
}

// Operation using the composed trait
@standardOperation(ttl: 60, metric: "GetThingLatency")
operation GetThing {
    input := {}
    output := {}
}
