using Npgsql;

// Runs the real SnapshotPollCoverage.Capture method against a disposable CI database.
// The source file is linked from the importer project (no copy of its SQL).
var cs = Environment.GetEnvironmentVariable("CW_IMPORT_CONNECTION")
    ?? throw new InvalidOperationException("Set CW_IMPORT_CONNECTION to an isolated TEST database.");
await using var db = new NpgsqlConnection(cs);
await db.OpenAsync();
await using var tx = await db.BeginTransactionAsync();
const string oldHash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
const string newHash = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
const string nextHash = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";
const string initial = "2026-10-25T08:00:00Z";
const string later = "2026-10-25T08:10:00Z";
const string latest = "2026-10-25T08:20:00Z";

async Task Exec(string sql) { await using var cmd = new NpgsqlCommand(sql, db, tx); await cmd.ExecuteNonQueryAsync(); }
async Task<long> Scalar(string sql) { await using var cmd = new NpgsqlCommand(sql,db,tx); return Convert.ToInt64(await cmd.ExecuteScalarAsync()); }

// Use the actual EVSE record imported by the HTTP fixture. All setup and assertions roll back.
if (await Scalar("SELECT count(*) FROM evse") != 1)
    throw new InvalidOperationException("Expected one EVSE from the isolated HTTP fixture.");

await Exec($"""
    DELETE FROM evse_poll_hour_coverage;
    DELETE FROM evse_poll_checkpoint;
    DELETE FROM ndw_snapshot_checkpoint WHERE source_name='ndw-locations';
    INSERT INTO ndw_snapshot_checkpoint(source_name,source_sha256,fetched_at,location_count,evse_count)
      VALUES ('ndw-locations','{oldHash}','{initial}',1,1);
    INSERT INTO evse_poll_checkpoint(evse_id,observed_at,status_class,source_updated_at,snapshot_sha256)
      SELECT id,'{initial}','available','2026-10-25 07:59+00','{oldHash}' FROM evse;
    CREATE TEMP TABLE stage_observation ON COMMIT DROP AS
      SELECT id AS evse_id,'available'::text AS status_class,
             '2026-10-25 08:09+00'::timestamptz AS source_updated_at FROM evse;
    """);
var at10 = DateTimeOffset.Parse(later);
await SnapshotPollCoverage.Capture(db, tx, at10, newHash);
await Exec("DROP TABLE poll_eligible, poll_previous;");
if (await Scalar("SELECT covered_seconds FROM evse_poll_hour_coverage") != 600)
    throw new InvalidOperationException("First 10-minute coverage interval was not 600 seconds.");

// The same poll again must not introduce a second 600-second increment.
// The importer advances the source checkpoint in the same transaction.
await Exec($"""
    UPDATE ndw_snapshot_checkpoint SET source_sha256='{newHash}',fetched_at='{later}'
       WHERE source_name='ndw-locations';
    """);
await SnapshotPollCoverage.Capture(db, tx, at10, newHash);
if (await Scalar("SELECT covered_seconds FROM evse_poll_hour_coverage") != 600)
    throw new InvalidOperationException("Replay double counted coverage.");

// Next consecutive feed observation, same status: another 600 seconds.
await Exec("UPDATE stage_observation SET source_updated_at='2026-10-25 08:19+00';");
await SnapshotPollCoverage.Capture(db, tx, DateTimeOffset.Parse(latest), nextHash);
await Exec("DROP TABLE poll_eligible, poll_previous;");
if (await Scalar("SELECT covered_seconds FROM evse_poll_hour_coverage") != 1200)
    throw new InvalidOperationException("Consecutive poll chain failed to cover 1200 seconds.");

// The status changes in another ten minutes: no continuity may be assumed.
await Exec($"""
    UPDATE ndw_snapshot_checkpoint SET source_sha256='{nextHash}',fetched_at='{latest}'
       WHERE source_name='ndw-locations';
    UPDATE stage_observation SET status_class='occupied',
        source_updated_at='2026-10-25 08:29+00';
    """);
await SnapshotPollCoverage.Capture(db, tx,
    DateTimeOffset.Parse("2026-10-25T08:30:00Z"),
    "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd");
await Exec("DROP TABLE poll_eligible, poll_previous;");
if (await Scalar("SELECT covered_seconds FROM evse_poll_hour_coverage") != 1200)
    throw new InvalidOperationException("A status change generated invented continuity.");

await tx.RollbackAsync();
Console.WriteLine("PASS: real SnapshotPollCoverage.Capture: consecutive, replay, status change (rolled back).");
