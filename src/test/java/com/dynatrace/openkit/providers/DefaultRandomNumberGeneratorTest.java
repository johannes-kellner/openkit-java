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
package com.dynatrace.openkit.providers;

import org.junit.Test;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.Assert.assertThat;

public class DefaultRandomNumberGeneratorTest {

    @Test
    public void nextPositiveLongReturnsPositiveLongValue() {
        // given
        DefaultRandomNumberGenerator target = new DefaultRandomNumberGenerator();

        for (int i = 0; i < 100; i++) {
            // when
            long obtained = target.nextPositiveLong();

            // then
            assertThat(obtained, greaterThanOrEqualTo(0L));
        }
    }
}
