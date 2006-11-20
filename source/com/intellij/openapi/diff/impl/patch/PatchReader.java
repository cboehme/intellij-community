/*
 * Copyright (c) 2000-2006 JetBrains s.r.o. All Rights Reserved.
 */

/*
 * Created by IntelliJ IDEA.
 * User: yole
 * Date: 15.11.2006
 * Time: 18:05:20
 */
package com.intellij.openapi.diff.impl.patch;

import com.intellij.openapi.fileEditor.impl.LoadTextUtil;
import com.intellij.openapi.util.text.LineTokenizer;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.List;
import java.util.ArrayList;

public class PatchReader {

  private enum DiffFormat { CONTEXT, UNIFIED }

  private String[] myLines;
  private int myLineIndex = 0;
  private DiffFormat myDiffFormat = null;
  @NonNls private static final String CONTEXT_HUNK_PREFIX = "***************";
  @NonNls private static final String CONTEXT_FILE_PREFIX = "*** ";
  @NonNls private static final Pattern ourUnifiedHunkStartPattern = Pattern.compile("@@ -(\\d+),(\\d+) \\+(\\d+),(\\d+) @@");
  @NonNls private static final Pattern ourContextBeforeHunkStartPattern = Pattern.compile("\\*\\*\\* (\\d+),(\\d+) \\*\\*\\*\\*");
  @NonNls private static final Pattern ourContextAfterHunkStartPattern = Pattern.compile("--- (\\d+),(\\d+) ----");

  public PatchReader(VirtualFile virtualFile) throws IOException {
    byte[] patchContents = virtualFile.contentsToByteArray();
    CharSequence patchText = LoadTextUtil.getTextByBinaryPresentation(patchContents, virtualFile);
    myLines = LineTokenizer.tokenize(patchText, false);
  }

  @Nullable
  public FilePatch readNextPatch() throws PatchSyntaxException {
    while (myLineIndex < myLines.length) {
      String curLine = myLines [myLineIndex];
      if (curLine.startsWith("--- ") && (myDiffFormat == null || myDiffFormat == DiffFormat.UNIFIED)) {
        myDiffFormat = DiffFormat.UNIFIED;
        return readPatch(curLine);
      }
      else if (curLine.startsWith(CONTEXT_FILE_PREFIX) && (myDiffFormat == null || myDiffFormat == DiffFormat.CONTEXT)) {
        myDiffFormat = DiffFormat.CONTEXT;
        return readPatch(curLine);
      }
      myLineIndex++;
    }
    return null;
  }

  private FilePatch readPatch(String curLine) throws PatchSyntaxException {
    final FilePatch curPatch;
    curPatch = new FilePatch();
    extractFileName(curLine, curPatch, true);
    myLineIndex++;
    curLine = myLines [myLineIndex];
    String secondNamePrefix = myDiffFormat == DiffFormat.UNIFIED ? "+++ " : "--- ";
    if (!curLine.startsWith(secondNamePrefix)) {
      throw new PatchSyntaxException(myLineIndex, "Second file name expected");
    }
    extractFileName(curLine, curPatch, false);
    myLineIndex++;
    while(myLineIndex < myLines.length) {
      PatchHunk hunk;
      if (myDiffFormat == DiffFormat.UNIFIED) {
        hunk = readNextHunkUnified();
      }
      else {
        hunk = readNextHunkContext();
      }
      if (hunk == null) break;
      curPatch.addHunk(hunk);
    }
    return curPatch;
  }

  @Nullable
  private PatchHunk readNextHunkUnified() throws PatchSyntaxException {
    while(myLineIndex < myLines.length) {
      String curLine = myLines [myLineIndex];
      if (curLine.startsWith("--- ")) {
        return null;
      }
      if (curLine.startsWith("@@ ")) {
        break;
      }
      myLineIndex++;
    }
    if (myLineIndex == myLines.length) {
      return null;
    }

    Matcher m = ourUnifiedHunkStartPattern.matcher(myLines [myLineIndex]);
    if (!m.matches()) {
      throw new PatchSyntaxException(myLineIndex, "Unknown hunk start syntax");
    }
    int startLineBefore = Integer.parseInt(m.group(1));
    int endLineBefore = Integer.parseInt(m.group(2));
    int startLineAfter = Integer.parseInt(m.group(3));
    int endLineAfter = Integer.parseInt(m.group(4));
    PatchHunk hunk = new PatchHunk(startLineBefore, endLineBefore, startLineAfter, endLineAfter);
    myLineIndex++;
    while(myLineIndex < myLines.length) {
      String curLine = myLines [myLineIndex];
      final PatchLine line = parsePatchLine(curLine, 1);
      if (line == null) {
        break;
      }
      hunk.addLine(line);
      myLineIndex++;
    }
    return hunk;
  }

  @Nullable
  private static PatchLine parsePatchLine(final String line, final int prefixLength) {
    PatchLine.Type type;
    if (line.startsWith("+")) {
      type = PatchLine.Type.ADD;
    }
    else if (line.startsWith("-")) {
      type = PatchLine.Type.REMOVE;
    }
    else if (line.startsWith(" ")) {
      type = PatchLine.Type.CONTEXT;
    }
    else {
      return null;
    }
    return new PatchLine(type, line.substring(prefixLength));
  }

  @Nullable
  private PatchHunk readNextHunkContext() throws PatchSyntaxException {
    while(myLineIndex < myLines.length) {
      String curLine = myLines [myLineIndex];
      if (curLine.startsWith(CONTEXT_FILE_PREFIX)) {
        return null;
      }
      if (curLine.startsWith(CONTEXT_HUNK_PREFIX)) {
        break;
      }
      myLineIndex++;
    }
    if (myLineIndex == myLines.length) {
      return null;
    }
    myLineIndex++;
    Matcher beforeMatcher = ourContextBeforeHunkStartPattern.matcher(myLines [myLineIndex]);
    if (!beforeMatcher.matches()) {
      throw new PatchSyntaxException(myLineIndex, "Unknown before hunk start syntax");
    }
    myLineIndex++;
    List<String> beforeLines = readContextDiffLines();
    if (myLineIndex == myLines.length) {
      throw new PatchSyntaxException(myLineIndex, "Missing after hunk");
    }
    Matcher afterMatcher = ourContextAfterHunkStartPattern.matcher(myLines [myLineIndex]);
    if (!afterMatcher.matches()) {
      throw new PatchSyntaxException(myLineIndex, "Unknown before hunk start syntax");
    }
    myLineIndex++;
    List<String> afterLines = readContextDiffLines();
    int startLineBefore = Integer.parseInt(beforeMatcher.group(1));
    int endLineBefore = Integer.parseInt(beforeMatcher.group(2));
    int startLineAfter = Integer.parseInt(afterMatcher.group(1));
    int endLineAfter = Integer.parseInt(afterMatcher.group(2));
    PatchHunk hunk = new PatchHunk(startLineBefore, endLineBefore, startLineAfter, endLineAfter);

    int beforeLineIndex = 0;
    int afterLineIndex = 0;
    if (beforeLines.size() == 0) {
      for(String line: afterLines) {
        hunk.addLine(parsePatchLine(line, 2));
      }
    }
    else if (afterLines.size() == 0) {
      for(String line: beforeLines) {
        hunk.addLine(parsePatchLine(line, 2));
      }
    }
    else {
      while(beforeLineIndex < beforeLines.size() && afterLineIndex < afterLines.size()) {
        String beforeLine = beforeLines.get(beforeLineIndex);
        String afterLine = afterLines.get(afterLineIndex);
        if (beforeLine.startsWith(" ") && afterLine.startsWith(" ")) {
          hunk.addLine(new PatchLine(PatchLine.Type.CONTEXT, beforeLine.substring(2)));
          beforeLineIndex++;
          afterLineIndex++;
        }
        else if (beforeLine.startsWith("-")) {
          hunk.addLine(new PatchLine(PatchLine.Type.REMOVE, beforeLine.substring(2)));
          beforeLineIndex++;
        }
        else if (afterLine.startsWith("+")) {
          hunk.addLine(new PatchLine(PatchLine.Type.ADD, afterLine.substring(2)));
          afterLineIndex++;
        }
        else if (beforeLine.startsWith("!") && afterLine.startsWith("!")) {
          while(beforeLineIndex < beforeLines.size() && beforeLines.get(beforeLineIndex).startsWith("! ")) {
            hunk.addLine(new PatchLine(PatchLine.Type.REMOVE, beforeLines.get(beforeLineIndex).substring(2)));
            beforeLineIndex++;
          }

          while(afterLineIndex < afterLines.size() && afterLines.get(afterLineIndex).startsWith("! ")) {
            hunk.addLine(new PatchLine(PatchLine.Type.ADD, afterLines.get(afterLineIndex).substring(2)));
            afterLineIndex++;
          }
        }
        else {
          throw new PatchSyntaxException(-1, "Unknown line prefix");
        }
      }
    }
    return hunk;
  }

  private List<String> readContextDiffLines() {
    ArrayList<String> result = new ArrayList<String>();
    while(myLineIndex < myLines.length) {
      final String line = myLines[myLineIndex];
      if (!line.startsWith("  ") && !line.startsWith("+ ") && !line.startsWith("- ") && !line.startsWith("! ")) {
        break;
      }
      result.add(line);
      myLineIndex++;
    }
    return result;
  }

  private static void extractFileName(final String curLine, final FilePatch patch, final boolean before) {
    String fileName = curLine.substring(4);
    int pos = fileName.indexOf('\t');
    if (pos < 0) {
      pos = fileName.indexOf(' ');
    }
    if (pos >= 0) {
      String versionId = fileName.substring(pos).trim();
      fileName = fileName.substring(0, pos);
      if (versionId.length() > 0) {
        if (before) {
          patch.setBeforeVersionId(versionId);
        }
        else {
          patch.setAfterVersionId(versionId);
        }
      }
    }
    if (before) {
      patch.setBeforeName(fileName);
    }
    else {
      patch.setAfterName(fileName);
    }
  }
}
