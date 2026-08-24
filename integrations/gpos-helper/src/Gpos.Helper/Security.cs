using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;

namespace Gpos.Helper;

public interface ISecretStore
{
    Task SaveAsync(string name, string value, CancellationToken cancellationToken);
    Task<string?> GetAsync(string name, CancellationToken cancellationToken);
    Task DeleteAsync(string name, CancellationToken cancellationToken);
}

public sealed class WindowsDpapiSecretStore(IHostEnvironment environment) : ISecretStore
{
    private readonly string _directory = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "GEMINI-POS", "gpos-helper", environment.EnvironmentName);

    public async Task SaveAsync(string name, string value, CancellationToken cancellationToken)
    {
        EnsureWindows();
        Directory.CreateDirectory(_directory);
        var clear = Encoding.UTF8.GetBytes(value);
        try { await File.WriteAllBytesAsync(PathFor(name), Protect(clear), cancellationToken); }
        finally { CryptographicOperations.ZeroMemory(clear); }
    }

    public async Task<string?> GetAsync(string name, CancellationToken cancellationToken)
    {
        EnsureWindows();
        var path = PathFor(name);
        if (!File.Exists(path)) return null;
        var cipher = await File.ReadAllBytesAsync(path, cancellationToken);
        var clear = Unprotect(cipher);
        try { return Encoding.UTF8.GetString(clear); }
        finally { CryptographicOperations.ZeroMemory(clear); }
    }

    public Task DeleteAsync(string name, CancellationToken cancellationToken)
    {
        EnsureWindows();
        var path = PathFor(name);
        if (File.Exists(path)) File.Delete(path);
        return Task.CompletedTask;
    }

    private string PathFor(string name) => Path.Combine(_directory, Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(name))) + ".bin");
    private static void EnsureWindows() { if (!OperatingSystem.IsWindows()) throw new PlatformNotSupportedException("DPAPI secret storage requires Windows."); }

    private static byte[] Protect(byte[] input) => Crypt(input, true);
    private static byte[] Unprotect(byte[] input) => Crypt(input, false);

    private static byte[] Crypt(byte[] input, bool protect)
    {
        var inputBlob = new DataBlob();
        var outputBlob = new DataBlob();
        try
        {
            inputBlob.Size = input.Length;
            inputBlob.Data = Marshal.AllocHGlobal(input.Length);
            Marshal.Copy(input, 0, inputBlob.Data, input.Length);
            var ok = protect
                ? CryptProtectData(ref inputBlob, "gpos-helper", IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, 1, ref outputBlob)
                : CryptUnprotectData(ref inputBlob, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, 1, ref outputBlob);
            if (!ok) throw new CryptographicException(Marshal.GetLastWin32Error());
            var result = new byte[outputBlob.Size];
            Marshal.Copy(outputBlob.Data, result, 0, outputBlob.Size);
            return result;
        }
        finally
        {
            if (inputBlob.Data != IntPtr.Zero) { ZeroMemory(inputBlob.Data, (nuint)inputBlob.Size); Marshal.FreeHGlobal(inputBlob.Data); }
            if (outputBlob.Data != IntPtr.Zero) { ZeroMemory(outputBlob.Data, (nuint)outputBlob.Size); LocalFree(outputBlob.Data); }
        }
    }

    [StructLayout(LayoutKind.Sequential)] private struct DataBlob { public int Size; public IntPtr Data; }
    [DllImport("crypt32.dll", SetLastError = true, CharSet = CharSet.Unicode)] private static extern bool CryptProtectData(ref DataBlob dataIn, string description, IntPtr entropy, IntPtr reserved, IntPtr prompt, int flags, ref DataBlob dataOut);
    [DllImport("crypt32.dll", SetLastError = true)] private static extern bool CryptUnprotectData(ref DataBlob dataIn, IntPtr description, IntPtr entropy, IntPtr reserved, IntPtr prompt, int flags, ref DataBlob dataOut);
    [DllImport("kernel32.dll", SetLastError = false)] private static extern IntPtr LocalFree(IntPtr memory);
    [DllImport("kernel32.dll", EntryPoint = "RtlZeroMemory")] private static extern void ZeroMemory(IntPtr destination, nuint length);
}

public static partial class SecretRedactor
{
    public static string Redact(string? input)
    {
        if (string.IsNullOrEmpty(input)) return input ?? "";
        var value = BearerRegex().Replace(input, "$1[REDACTED]");
        value = JsonSecretRegex().Replace(value, "$1[REDACTED]$2");
        value = QuerySecretRegex().Replace(value, "$1[REDACTED]");
        return value;
    }

    [GeneratedRegex("(?i)(bearer\\s+)[A-Za-z0-9._~+/-]+=*")] private static partial Regex BearerRegex();
    [GeneratedRegex("(?i)(\\\"(?:access_token|refresh_token|id_token|auth_token|client_secret|password|api_key)\\\"\\s*:\\s*\\\")[^\\\"]*(\\\")")] private static partial Regex JsonSecretRegex();
    [GeneratedRegex("(?i)((?:token|key|secret|password)=)[^&\\s]+")] private static partial Regex QuerySecretRegex();
}

