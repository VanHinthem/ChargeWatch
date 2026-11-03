using System.Text.RegularExpressions;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.Primitives;
using Npgsql;
using NpgsqlTypes;

// Standalone Q4 contract probe; includes the production MapFilter.cs as a linked source.
// No database connection and no production API changes.
if (args.Length == 1 && args[0] == "--emit-overview-geofirst")
{
    Console.WriteLine(OverviewGeoFirstExporter.Create());
    return;
}
if (args.Length == 1 && args[0] == "--emit-overview-perf")
{
    Console.WriteLine(OverviewPerformanceExporter.Create());
    return;
}
if (args.Length == 1 && args[0] == "--emit-q4c01")
{
    Console.WriteLine(Q4cSqlExporter.CreateCcs2Dc());
    return;
}
if (args.Length == 1 && args[0] == "--emit-q4c02")
{
    Console.WriteLine(Q4cSqlExporter.CreatePowerBands());
    return;
}
if (args.Length == 1 && args[0] == "--emit-q4c02b")
{
    Console.WriteLine(Q4cPowerBoundaryExporter.Create());
    return;
}
if (args.Length == 1 && args[0] == "--emit-q4c03")
{
    Console.WriteLine(Q4cSqlExporter.CreateMinFree());
    return;
}
if (args.Length == 1 && args[0] == "--emit-q4c04")
{
    Console.WriteLine(Q4cSqlExporter.CreateCombinedMinFree());
    return;
}
if (args.Length == 1 && args[0] == "--emit-q4c04b")
{
    Console.WriteLine(Q4cEvseAuditExporter.Create());
    return;
}
if (args.Length == 1 && args[0] == "--emit-q4c05")
{
    Console.WriteLine(Q4cSqlExporter.CreateOperators());
    return;
}
if (args.Length == 1 && args[0] == "--emit-q4c06")
{
    Console.WriteLine(Q4cSqlExporter.CreateMultiConnectorMode());
    return;
}
if (args.Length == 1 && args[0] == "--emit-q4c07")
{
    Console.WriteLine(Q4cSqlExporter.CreateNoMatch());
    return;
}
if (args.Length == 1 && args[0] == "--emit-q4c08")
{
    Console.WriteLine(Q4cSqlExporter.CreateRingScope());
    return;
}
if (args.Length != 0)
    throw new ArgumentException("Usage: MapFilterQ4Probe [--emit-q4c01|--emit-q4c02|--emit-q4c02b|--emit-q4c03|--emit-q4c04|--emit-q4c04b|--emit-q4c05|--emit-q4c06|--emit-q4c07|--emit-q4c08|--emit-overview-perf|--emit-overview-geofirst]");

MapFilter.SelfTest();

var cases = new (string Name, (string Key, string[] Values)[] Entries)[]
{
    ("Q4-00 none", []),
    ("Q4-01 CCS2 DC", [("connectorType", ["ccs2"]), ("mode", ["dc"])]),
    ("Q4-02 lt50", [("power", ["lt50"])]),
    ("Q4-02 gte50", [("power", ["gte50"])]),
    ("Q4-02 gte150", [("power", ["gte150"])]),
    ("Q4-02 gte300", [("power", ["gte300"])]),
    ("Q4-03 minFree1", [("minFree", ["1"])]),
    ("Q4-03 minFree2", [("minFree", ["2"])]),
    ("Q4-04 combined", [("connectorType", ["ccs2"]), ("mode", ["dc"]), ("power", ["gte150"]), ("minFree", ["2"])]),
    ("Q4-05 operators", [("operator", ["Operator A", "Operator B", "Operator A"])]),
    ("Q4-06 multi", [("connectorType", ["ccs2", "chademo"]), ("mode", ["ac", "dc"])]),
    ("Q4-07 no-match candidate", [("operator", ["__Q4_NONEXISTENT_OPERATOR__"])]),
    ("Q4-08 combined availability", [("connectorType", ["ccs2"]), ("power", ["gte150"]), ("minFree", ["1"])]),
    ("all parameter arrays", [("operator", ["Operator A"]), ("connectorType", ["ccs2"]), ("mode", ["dc"])])
};

foreach (var (name, entries) in cases)
{
    var query = new QueryCollection(entries.ToDictionary(
        e => e.Key, e => new StringValues(e.Values)));
    Check(MapFilter.TryParse(query, out var filter, out var error), $"{name}: parse failed: {error}");

    using var viewport = new NpgsqlCommand();
    using var overview = new NpgsqlCommand();
    AddPrefix(viewport, 4);
    AddPrefix(overview, 5);
    filter.AddParameters(viewport);
    filter.AddParameters(overview);

    var oldPredicate = filter.LocationPredicate(5);
    var newPredicate = filter.LocationPredicate(6);
    var referencesOld = ReferencedParameters(oldPredicate);
    var referencesNew = ReferencedParameters(newPredicate);
    var expectedOld = Enumerable.Range(5, viewport.Parameters.Count - 4).ToArray();
    var expectedNew = Enumerable.Range(6, overview.Parameters.Count - 5).ToArray();

    Check(referencesOld.SequenceEqual(expectedOld), $"{name}: viewport references mismatch");
    Check(referencesNew.SequenceEqual(expectedNew), $"{name}: overview references mismatch");
    Check(viewport.Parameters.Count + 1 == overview.Parameters.Count, $"{name}: prefix count mismatch");
    Check(Normalize(newPredicate, 1) == oldPredicate, $"{name}: predicate changed beyond positional shift");

    foreach (var parameter in viewport.Parameters.Cast<NpgsqlParameter>().Skip(4)
                 .Concat(overview.Parameters.Cast<NpgsqlParameter>().Skip(5)))
        Check(parameter.NpgsqlDbType == (NpgsqlDbType.Array | NpgsqlDbType.Text),
            $"{name}: array must be text[]");

    var oldArrays = viewport.Parameters.Cast<NpgsqlParameter>().Skip(4)
        .Select(p => (string[])p.Value!).ToArray();
    var newArrays = overview.Parameters.Cast<NpgsqlParameter>().Skip(5)
        .Select(p => (string[])p.Value!).ToArray();
    Check(oldArrays.Length == newArrays.Length, $"{name}: array count differs");
    for (var i = 0; i < oldArrays.Length; i++)
        Check(oldArrays[i].SequenceEqual(newArrays[i]), $"{name}: parameter values differ at {i}");

    Console.WriteLine($"PASS {name}: viewport={viewport.Parameters.Count} overview={overview.Parameters.Count}, filterArrays={oldArrays.Length}");
}
Console.WriteLine($"PASS: {cases.Length} Q4 generator/parameter contract cases. No SQL/database execution.");

static void AddPrefix(NpgsqlCommand command, int count)
{
    for (var i = 0; i < count; i++)
        command.Parameters.Add(new NpgsqlParameter { NpgsqlDbType = NpgsqlDbType.Double, Value = 1.0 });
}

static int[] ReferencedParameters(string sql) =>
    Regex.Matches(sql, @"\$(\d+)")
        .Select(m => int.Parse(m.Groups[1].Value))
        .Distinct().Order().ToArray();

static string Normalize(string sql, int delta) =>
    Regex.Replace(sql, @"\$(\d+)", m => "$" + (int.Parse(m.Groups[1].Value) - delta));

static void Check(bool condition, string message)
{
    if (!condition) throw new InvalidOperationException(message);
}
