# Glaive Bridge Integration for Nabu

This document defines how Nabu can discover and execute Glaive file tools through Android IPC.

## Overview

Glaive exposes:

- A `ContentProvider` for tool discovery
- An exported `Activity` for tool execution

Bridge package IDs:

- App package: `com.mewmix.glaive`
- Provider authority: `com.mewmix.glaive.tool_provider`
- Execute action: `com.mewmix.glaive.ACTION_EXECUTE_TOOL`

## Security Requirement

Both the provider and bridge activity require:

- Permission: `com.mewmix.glaive.permission.ACCESS_TOOLS`
- Protection level: `signature`

Because this is `signature` protected, Nabu must be signed with the same signing certificate as Glaive to call these APIs.

## Discover Available Tools

Query:

- URI: `content://com.mewmix.glaive.tool_provider`
- MIME type: `vnd.android.cursor.dir/vnd.com.mewmix.glaive.tools`

Returned columns:

- `name` (string)
- `description` (string)
- `parameters` (JSON object encoded as string)

Kotlin example:

```kotlin
val uri = Uri.parse("content://com.mewmix.glaive.tool_provider")
context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
    val nameIdx = cursor.getColumnIndexOrThrow("name")
    val descIdx = cursor.getColumnIndexOrThrow("description")
    val paramsIdx = cursor.getColumnIndexOrThrow("parameters")
    while (cursor.moveToNext()) {
        val name = cursor.getString(nameIdx)
        val description = cursor.getString(descIdx)
        val paramsJson = cursor.getString(paramsIdx)
    }
}
```

## Execute a Tool

Use explicit intent to `BridgeActivity`:

- Action: `com.mewmix.glaive.ACTION_EXECUTE_TOOL`
- Extras:
  - `TOOL_NAME` (string, required)
  - `TOOL_PARAMS` (stringified JSON object, optional)

Result extras:

- Success: `TOOL_RESULT` (string)
- Error: `TOOL_ERROR` (string)

Kotlin example:

```kotlin
val intent = Intent("com.mewmix.glaive.ACTION_EXECUTE_TOOL").apply {
    setClassName("com.mewmix.glaive", "com.mewmix.glaive.bridge.BridgeActivity")
    putExtra("TOOL_NAME", "list_files")
    putExtra("TOOL_PARAMS", JSONObject().put("path", "/sdcard/Download").toString())
}
launcher.launch(intent)
```

Activity result handling:

```kotlin
val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
    val data = result.data
    val output = data?.getStringExtra("TOOL_RESULT")
    val error = data?.getStringExtra("TOOL_ERROR")
}
```

## Tool Catalog

1. `list_files`
   - Params: `{"path":"string"}`
   - Result: JSON array with `name`, `path`, `isDirectory`, `length`, `lastModified`
2. `read_file`
   - Params: `{"path":"string"}`
   - Limit: max 1MB
   - Result: raw file text
3. `write_file`
   - Params: `{"path":"string","content":"string"}`
   - Result: `"Success"` or error
4. `create_dir`
   - Params: `{"path":"string"}`
   - Result: success/failure string
5. `delete_file`
   - Params: `{"path":"string"}`
   - Result: success/failure string
6. `search_files`
   - Params: `{"root_path":"string","query":"string"}`
   - Result: JSON array with `name`, `path`, `type`, `size`, `mtime`

## Error Semantics

`TOOL_ERROR` is populated when:

- Required params are missing
- Tool name is unknown
- File operation fails
- Any runtime exception is raised in bridge execution

Treat `TOOL_ERROR != null` as failure even if Android result code is `RESULT_OK`.

## Integration Notes

- Keep `TOOL_PARAMS` as compact JSON strings.
- Use path allowlists on the Nabu side to avoid destructive calls to unexpected locations.
- For `write_file` and `delete_file`, require explicit user confirmation in Nabu UI before execution.
