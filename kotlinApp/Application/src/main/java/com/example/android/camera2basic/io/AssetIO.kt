package com.example.android.camera2basic.io

import android.content.Context
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader


/**
 * Helper for getting strings from any file type in /assets/ folder. Primarily used for shaders.
 *
 * @param ctx Context to use
 * @param filename name of the file, including any folders, inside of the /assets/ folder.
 * @return String of contents of file, lines separated by `\n`
 * @throws java.io.IOException if file is not found
 */
@Throws(IOException::class)
fun getStringFromFileInAssets(ctx: Context, filename: String): String {
    return getStringFromFileInAssets(ctx, filename, true)
}

@Throws(IOException::class)
fun getStringFromFileInAssets(ctx: Context, filename: String, useNewline: Boolean): String {
    val inputStream = ctx.assets.open(filename)
    val reader = BufferedReader(InputStreamReader(inputStream))
    val builder = StringBuilder()
    var line: String? = reader.readLine()
    while (line != "null") {
        builder.append(line + if (useNewline) "\n" else "")
        line = reader.readLine() ?: "null"
    }
    inputStream.close()
    return builder.toString()
}