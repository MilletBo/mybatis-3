/**
 *    Copyright 2009-2017 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.autoconstructor;

/**
 * 对应CreateDB.sql中的subject表
 * 和AnnotatedSubject不同，在其构造方法上，height方法参数的类型是Height，而不是integer
 * 因为MyBatis无法识别Height类，所以会创建BadSubject对象报错
 * @author milletbo
 *	一般情况下，POJO对象里，不实用基本类型
 */
public class BadSubject {
  private final int id;
  private final String name;
  private final int age;
  private final Height height;
  private final Double weight;

  public BadSubject(final int id, final String name, final int age, final Height height, final Double weight) {
    this.id = id;
    this.name = name;
    this.age = age;
    this.height = height;
    this.weight = weight == null ? 0 : weight;
  }

  private class Height {

  }
}
