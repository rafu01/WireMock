/*
 * Copyright (C) 2016-2021 Thomas Akehurst
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.tomakehurst.wiremock.matching;

import static com.github.tomakehurst.wiremock.common.LocalNotifier.notifier;
import static java.util.stream.Collectors.toList;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.github.tomakehurst.wiremock.common.Json;
import com.github.tomakehurst.wiremock.common.ListOrSingle;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import java.util.*;

@JsonSerialize(using = JsonPathPatternJsonSerializer.class)
public class MatchesJsonPathPattern extends PathPattern {

  public MatchesJsonPathPattern(
      @JsonProperty("matchesJsonPath") String expectedJsonPath, StringValuePattern valuePattern) {
    super(expectedJsonPath, valuePattern);
  }

  public MatchesJsonPathPattern(String value) {
    this(value, null);
  }

  public String getMatchesJsonPath() {
    return expectedValue;
  }

  protected MatchResult isSimpleMatch(String value) {
    // For performance reason, don't try to parse XML value
    if (value != null && value.trim().startsWith("<")) {
      notifier()
          .info(
              String.format(
                  "Warning: JSON path expression '%s' failed to match document '%s' because it's not JSON document",
                  expectedValue, value));
      return MatchResult.noMatch();
    }
    try {
      Object obj = JsonPath.read(value, expectedValue);

      boolean result;
      if (obj instanceof Collection) {
        result = !((Collection) obj).isEmpty();
      } else if (obj instanceof Map) {
        result = !((Map) obj).isEmpty();
      } else {
        result = obj != null;
      }

      return MatchResult.of(result);
    } catch (Exception e) {
      String error;
      if (e.getMessage().equalsIgnoreCase("invalid path")) {
        error = "the JSON path didn't match the document structure";
      } else if (e.getMessage().equalsIgnoreCase("invalid container object")) {
        error = "the JSON document couldn't be parsed";
      } else {
        error = "of error '" + e.getMessage() + "'";
      }

      String message =
          String.format(
              "Warning: JSON path expression '%s' failed to match document '%s' because %s",
              expectedValue, value, error);
      notifier().info(message);

      return MatchResult.noMatch();
    }
  }

  protected MatchResult isAdvancedMatch(String value) {
    try {
      ListOrSingle<String> expressionResult = getExpressionResult(value);

      // Bit of a hack, but otherwise empty array results aren't matched as absent()
      if ((expressionResult == null || expressionResult.isEmpty())
          && AbsentPattern.class.isAssignableFrom(valuePattern.getClass())) {
        expressionResult = ListOrSingle.of((String) null);
      }

      return expressionResult.stream()
          .map(valuePattern::match)
          .min(Comparator.comparingDouble(MatchResult::getDistance))
          .orElse(MatchResult.noMatch());
    } catch (SubExpressionException e) {
      notifier().info(e.getMessage());
      return MatchResult.noMatch();
    }
  }

  @Override
  public ListOrSingle<String> getExpressionResult(final String value) {
    // For performance reason, don't try to parse XML value
    if (value != null && value.trim().startsWith("<")) {
      final String message =
          String.format(
              "Warning: JSON path expression '%s' failed to match document '%s' because it's not JSON document",
              expectedValue, value);
      notifier().info(message);
      throw new SubExpressionException(message);
    }

    Object obj = null;
    try {
      obj = JsonPath.read(value, expectedValue);
    } catch (PathNotFoundException pnfe) {
    } catch (Exception e) {
      String error;
      if (e.getMessage().equalsIgnoreCase("invalid container object")) {
        error = "the JSON document couldn't be parsed";
      } else {
        error = "of error '" + e.getMessage() + "'";
      }

      String message =
          String.format(
              "Warning: JSON path expression '%s' failed to match document '%s' because %s",
              expectedValue, value, error);

      throw new SubExpressionException(message, e);
    }

    ListOrSingle<String> expressionResult;
    if (obj instanceof Map || EqualToJsonPattern.class.isAssignableFrom(valuePattern.getClass())) {
      expressionResult = ListOrSingle.of(Json.write(obj));
    } else if (obj instanceof List) {
      final List<String> stringValues =
          ((List<?>) obj).stream().map(Object::toString).collect(toList());
      expressionResult = ListOrSingle.of(stringValues);
    } else if (obj instanceof Number || obj instanceof String || obj instanceof Boolean) {
      expressionResult = ListOrSingle.of(String.valueOf(obj));
    } else {
      expressionResult = ListOrSingle.of();
    }

    return expressionResult;
  }
}
