/**
 * Copyright 2018-2020 Dynatrace LLC
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

package com.dynatrace.openkit.util.json.objects;

import org.junit.Test;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

public class JSONNullValueTest {

    @Test
    public void isNullReturnsTrue() {
        // then
        assertThat(JSONNullValue.NULL.isNull(), is(true));
    }

    @Test
    public void isBooleanReturnsFalse() {
        // then
        assertThat(JSONNullValue.NULL.isBoolean(), is(false));
    }

    @Test
    public void isNumberReturnsFalse() {
        // then
        assertThat(JSONNullValue.NULL.isNumber(), is(false));
    }

    @Test
    public void isStringReturnsFalse() {
        // then
        assertThat(JSONNullValue.NULL.isString(), is(false));
    }

    @Test
    public void isArrayReturnsFalse() {
        // then
        assertThat(JSONNullValue.NULL.isArray(), is(false));
    }

    @Test
    public void isObjectReturnsFalse() {
        // then
        assertThat(JSONNullValue.NULL.isObject(), is(false));
    }
}
