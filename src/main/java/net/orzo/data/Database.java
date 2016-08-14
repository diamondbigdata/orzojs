/*
 * Copyright (C) 2015 Tomas Machalek
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

package net.orzo.data;

import java.sql.SQLException;
import java.util.Iterator;

/**
 * Tomas Machalek <tomas.machalek@gmail.com>
 */
public interface Database {

    Iterator<Object[]> select(String query, Object...args) throws SQLException;

    void modify(String query, Object...args) throws SQLException;

    void close() throws SQLException;

}
