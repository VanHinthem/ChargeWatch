// NDW GeoJSON observed in Oss: NL-<three-character OCPI party_id>-<OCPI location source_id>.
// This is a validated sample pattern, not a universal promise by NDW.
internal readonly record struct NdwLocationKey(string CountryCode, string PartyId, string SourceId)
{
    public static bool TryParse(string? featureId, out NdwLocationKey key)
    {
        key = default;
        if (string.IsNullOrEmpty(featureId) || featureId.Length > 260 || !featureId.StartsWith("NL-", StringComparison.Ordinal))
            return false;

        const int sourceStart = 7; // NL- + 3-char OCPI party ID + '-'
        if (featureId.Length <= sourceStart || featureId[6] != '-')
            return false;
        var party = featureId.Substring(3, 3);
        if (!party.All(c => c is >= 'A' and <= 'Z' or >= '0' and <= '9'))
            return false;

        var source = featureId[sourceStart..];
        // Source IDs may contain hyphens; never split the remainder.
        // Only allow safe printable OCPI-ID characters; malformed/unexpected IDs fall back to NDW-only details.
        if (source.Length > 255 || !source.All(c =>
            c is >= 'A' and <= 'Z' or >= 'a' and <= 'z' or >= '0' and <= '9' or '-' or '_' or '.' or ':'))
            return false;
        key = new NdwLocationKey("NL", party, source);
        return true;
    }

    public static void SelfTest()
    {
        static void Valid(string id, string party, string source)
        {
            if (!TryParse(id, out var result) || result.PartyId != party || result.SourceId != source)
                throw new InvalidOperationException("NDW ID parsing failed: " + id);
        }

        Valid("NL-505-91056291", "505", "91056291");
        Valid("NL-NUO-0bf8edee-f912-4891-8b4c-ad709ff523fb", "NUO", "0bf8edee-f912-4891-8b4c-ad709ff523fb");
        Valid("NL-EFL-650dc75ff063f600200cc198", "EFL", "650dc75ff063f600200cc198");
        Valid("NL-LNT-3966410", "LNT", "3966410");
        foreach (var value in new[] { "", "NL-505-", "NL--123", "BE-505-123", "NL-ABC-hello/there",
                                     "NL-abc-123", "NL-AB-123", "NL-ABCD-123", "NL-ABC- bad" })
        {
            if (TryParse(value, out _))
                throw new InvalidOperationException("Unexpected valid NDW ID: " + value);
        }
    }
}
