/**
 *    Copyright 2009-2018 the original author or authors.
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
package org.apache.ibatis.reflection;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.ReflectPermission;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.ibatis.reflection.invoker.GetFieldInvoker;
import org.apache.ibatis.reflection.invoker.Invoker;
import org.apache.ibatis.reflection.invoker.MethodInvoker;
import org.apache.ibatis.reflection.invoker.SetFieldInvoker;
import org.apache.ibatis.reflection.property.PropertyNamer;

/**
 * This class represents a cached set of class definition information that
 * allows for easy mapping between property names and getter/setter methods.
 *
 *反射器，每个Reflector对应一个类，Reflector会缓存反射操作所需要的类的信息
 * @author Clinton Begin
 */
public class Reflector {

  /**
   * 对应的类
   */
  private final Class<?> type;
  /**
   * 可读属性数组
   */
  private final String[] readablePropertyNames;
  /**
   * 可写属性集合
   */
  private final String[] writeablePropertyNames;
  /**
   * 属性对应setting方法的映射
   * key 为属性名称
   * val 为invoker对象
   */
  private final Map<String, Invoker> setMethods = new HashMap<String, Invoker>();
  /**
   * 属性对应getting方法的映射
   * key 为属性名称
   * val 为invoker对象
   */
  private final Map<String, Invoker> getMethods = new HashMap<String, Invoker>();
  /**
   * 属性对应seting方法的方法参数类型的映射。 {@link #setMethods}
   * key 为属性名称
   * val 为返回值的类型
   */
  private final Map<String, Class<?>> setTypes = new HashMap<String, Class<?>>();
  /**
   * 属性对应getting方法的方法参数类型的映射。{@link #getMethods}
   * key 为属性名称
   * val 为返回值的类型 
   */
  private final Map<String, Class<?>> getTypes = new HashMap<String, Class<?>>();
  /**
   * 默认构造方法
   */
  private Constructor<?> defaultConstructor;

  /**
   * 不区分大小写的属性集合
   */
  private Map<String, String> caseInsensitivePropertyMap = new HashMap<String, String>();

  /**
   * 公共的构造方法
   * @param clazz
   */
  public Reflector(Class<?> clazz) {
	// 设置对应的类ß
    type = clazz;
    // 初始化默认构造器addDefaultConstructor
    addDefaultConstructor(clazz);
    // 初始化 getMethods和getypes， 通过遍历getting方法
    addGetMethods(clazz);
    // 初始化 setMethodssetTypes，  通过遍历setting方法
    addSetMethods(clazz);
    // 初始化getMethods + getTypes 和 setMethods + getTypes, 通过遍历fields属性 
    addFields(clazz);
    // 初始化 readablePropertyNames、writeablePropertyNames、caseInsensitivePropertyMap属性
    readablePropertyNames = getMethods.keySet().toArray(new String[getMethods.keySet().size()]);
    writeablePropertyNames = setMethods.keySet().toArray(new String[setMethods.keySet().size()]);
    for (String propName : readablePropertyNames) {
      caseInsensitivePropertyMap.put(propName.toUpperCase(Locale.ENGLISH), propName);
    }
    for (String propName : writeablePropertyNames) {
      caseInsensitivePropertyMap.put(propName.toUpperCase(Locale.ENGLISH), propName);
    }
  }

  private void addDefaultConstructor(Class<?> clazz) {
	// 获取所有构造的方法
    Constructor<?>[] consts = clazz.getDeclaredConstructors();
    // 遍历所有构造方法， 查无参的构造方法
    for (Constructor<?> constructor : consts) {
      // 判断无参的构造方法
      if (constructor.getParameterTypes().length == 0) {
    	// 设置构造方法可以访问， 避免是private等修饰符
        if (canAccessPrivateMethods()) {
          try {
            constructor.setAccessible(true);
          } catch (Exception e) {
            // Ignored. This is only a final precaution, nothing we can do.
          }
        }
        // 如果构造方法可以访问， 赋值给defaultConstructor
        if (constructor.isAccessible()) {
          this.defaultConstructor = constructor;
        }
      }
    }
  }

  /**
   * 通过遍历getting方法初始化getMethods和getTypes
   * @param cls
   */
  private void addGetMethods(Class<?> cls) {
	// 属性与其getting方法的映射
    Map<String, List<Method>> conflictingGetters = new HashMap<String, List<Method>>();
    // 获得所有方法
    Method[] methods = getClassMethods(cls);
    // 遍历所有方法
    for (Method method : methods) {
      // 参数大于0，说明不是getting方法，则忽略
      if (method.getParameterTypes().length > 0) {
        continue;
      }
      // 以get和is方法明开头，说明是getting方法
      String name = method.getName();
      if ((name.startsWith("get") && name.length() > 3)
          || (name.startsWith("is") && name.length() > 2)) {
    	// 获得属性
        name = PropertyNamer.methodToProperty(name);
        // 添加到conflictinggetters中	
        addMethodConflict(conflictingGetters, name, method);
      }
    }
    // 解决getting冲突方法
    resolveGetterConflicts(conflictingGetters);
  }

  /**
   * 解决getting冲突，最终一个属性，只保留一个对应的方法
   * @param conflictingGetters
   */
  private void resolveGetterConflicts(Map<String, List<Method>> conflictingGetters) {
	 // 遍历每个属性，查找最其匹配的方法。因为子类可以覆写父类的方法，所以一个属性可能对应多个getting方法
    for (Entry<String, List<Method>> entry : conflictingGetters.entrySet()) {
      Method winner = null; // 最匹配的方法
      String propName = entry.getKey();
      for (Method candidate : entry.getValue()) {
    	// winer为空，说明candidate为最匹配的方法
        if (winner == null) {
          winner = candidate;
          continue;
        }
        // 基于返回类型比较
        Class<?> winnerType = winner.getReturnType();
        Class<?> candidateType = candidate.getReturnType();
        // 类型相同
        if (candidateType.equals(winnerType)) {
          // 返回值了诶选哪个相同，应该在getClassMethods方法中，已经合并，所以抛ReflectionException
          if (!boolean.class.equals(candidateType)) {
            throw new ReflectionException(
                "Illegal overloaded getter method with ambiguous type for property "
                    + propName + " in class " + winner.getDeclaringClass()
                    + ". This breaks the JavaBeans specification and can cause unpredictable results.");
          // 选择boolean类型的is方法
          } else if (candidate.getName().startsWith("is")) {
            winner = candidate;
          }
        // 不符合选择子类
        } else if (candidateType.isAssignableFrom(winnerType)) {
          // OK getter type is descendant
        // 符合选择子类，因为子类可以修改放大返回值。例如：父类的一个方法的返回值为List,子类对该方法的返回值可以覆写为ArrayList.
        } else if (winnerType.isAssignableFrom(candidateType)) {
          winner = candidate;
        // 返回类型冲突，抛出ReflectionException异常
        } else {
          throw new ReflectionException(
              "Illegal overloaded getter method with ambiguous type for property "
                  + propName + " in class " + winner.getDeclaringClass()
                  + ". This breaks the JavaBeans specification and can cause unpredictable results.");
        }
      }
      // 添加到getMethods和getTypes中
      addGetMethod(propName, winner);
    }
  }

  private void addGetMethod(String name, Method method) {
	// 判读是合理的属性名
    if (isValidPropertyName(name)) {
      // 添加到getMethods中
      getMethods.put(name, new MethodInvoker(method));
      // 添加到getTypes中
      Type returnType = TypeParameterResolver.resolveReturnType(method, type);
      getTypes.put(name, typeToClass(returnType));
    }
  }

  /**
   * 通过遍历getting方法初始化setMethods、getTypes
   * @param cls
   */
  private void addSetMethods(Class<?> cls) {
	// 属性与其setting方法的映射
    Map<String, List<Method>> conflictingSetters = new HashMap<String, List<Method>>();
    // 获取所有方法
    Method[] methods = getClassMethods(cls);
    // 遍历所有方法
    for (Method method : methods) {
      String name = method.getName();
      // 方法名以 set 开头，参数数量为1
      if (name.startsWith("set") && name.length() > 3) {
        if (method.getParameterTypes().length == 1) {
          // 获得属性
          name = PropertyNamer.methodToProperty(name);
          // 添加到conflictingSetters中
          addMethodConflict(conflictingSetters, name, method);
        }
      }
    }
    // 解决setting冲突方法
    resolveSetterConflicts(conflictingSetters);
  }

  private void addMethodConflict(Map<String, List<Method>> conflictingMethods, String name, Method method) {
    List<Method> list = conflictingMethods.get(name);
    if (list == null) {
      list = new ArrayList<Method>();
      conflictingMethods.put(name, list);
    }
    list.add(method);
    /*
     * jdk1.8写法
     */
//    List<Method> list = conflictingMethods.computeIfAbsent(name, k -> new ArrayList<>());
//    list.add(method);
  }

  /**
   * 解决setting冲突
   * @param conflictingSetters
   */
  private void resolveSetterConflicts(Map<String, List<Method>> conflictingSetters) {
	// 遍历每个属性，查找其最匹配的方法，因为子类可以覆写父类的方法，所以一个属性，可能对应多个setting方法
    for (String propName : conflictingSetters.keySet()) {
      List<Method> setters = conflictingSetters.get(propName);
      Class<?> getterType = getTypes.get(propName);
      Method match = null;
      ReflectionException exception = null;
      // 遍历属性对应的setting方法
      for (Method setter : setters) {
        Class<?> paramType = setter.getParameterTypes()[0];
        // 和getTypes相同，直接使用
        if (paramType.equals(getterType)) {
          // should be the best match
          match = setter;
          break;
        }
        if (exception == null) {
          try {
        	// 选择一个更加匹配的
            match = pickBetterSetter(match, setter, propName);
          } catch (ReflectionException e) {
            // there could still be the 'best match'
            match = null;
            exception = e;
          }
        }
      }
      // 添加到setMethods和setTypes中
      if (match == null) {
        throw exception;
      } else {
        addSetMethod(propName, match);
      }
    }
  }

  private Method pickBetterSetter(Method setter1, Method setter2, String property) {
    if (setter1 == null) {
      return setter2;
    }
    Class<?> paramType1 = setter1.getParameterTypes()[0];
    Class<?> paramType2 = setter2.getParameterTypes()[0];
    if (paramType1.isAssignableFrom(paramType2)) {
      return setter2;
    } else if (paramType2.isAssignableFrom(paramType1)) {
      return setter1;
    }
    throw new ReflectionException("Ambiguous setters defined for property '" + property + "' in class '"
        + setter2.getDeclaringClass() + "' with types '" + paramType1.getName() + "' and '"
        + paramType2.getName() + "'.");
  }

  private void addSetMethod(String name, Method method) {
    if (isValidPropertyName(name)) {
      setMethods.put(name, new MethodInvoker(method));
      Type[] paramTypes = TypeParameterResolver.resolveParamTypes(method, type);
      setTypes.put(name, typeToClass(paramTypes[0]));
    }
  }

  /**
   * 寻找Type真正的类
   * @param src
   * @return
   */
  private Class<?> typeToClass(Type src) {
    Class<?> result = null;
    // 普通类型，直接使用类
    if (src instanceof Class) {
      result = (Class<?>) src;
    // 泛值类型，使用泛型
    } else if (src instanceof ParameterizedType) {
      result = (Class<?>) ((ParameterizedType) src).getRawType();
    // 泛型数组，获得具体类
    } else if (src instanceof GenericArrayType) {
      Type componentType = ((GenericArrayType) src).getGenericComponentType();
      // 普通类型
      if (componentType instanceof Class) {
        result = Array.newInstance((Class<?>) componentType, 0).getClass();
      } else {
    	// 递归该方法，返回类
        Class<?> componentClass = typeToClass(componentType);
        result = Array.newInstance((Class<?>) componentClass, 0).getClass();
      }
    }
    // 都不符合，使用Object类
    if (result == null) {
      result = Object.class;
    }
    return result;
  }

  private void addFields(Class<?> clazz) {
    Field[] fields = clazz.getDeclaredFields();
    for (Field field : fields) {
      if (canAccessPrivateMethods()) {
        try {
          field.setAccessible(true);
        } catch (Exception e) {
          // Ignored. This is only a final precaution, nothing we can do.
        }
      }
      if (field.isAccessible()) {
        if (!setMethods.containsKey(field.getName())) {
          // issue #379 - removed the check for final because JDK 1.5 allows
          // modification of final fields through reflection (JSR-133). (JGB)
          // pr #16 - final static can only be set by the classloader
          int modifiers = field.getModifiers();
          if (!(Modifier.isFinal(modifiers) && Modifier.isStatic(modifiers))) {
            addSetField(field);
          }
        }
        if (!getMethods.containsKey(field.getName())) {
          addGetField(field);
        }
      }
    }
    if (clazz.getSuperclass() != null) {
      addFields(clazz.getSuperclass());
    }
  }

  private void addSetField(Field field) {
    if (isValidPropertyName(field.getName())) {
      setMethods.put(field.getName(), new SetFieldInvoker(field));
      Type fieldType = TypeParameterResolver.resolveFieldType(field, type);
      setTypes.put(field.getName(), typeToClass(fieldType));
    }
  }

  private void addGetField(Field field) {
    if (isValidPropertyName(field.getName())) {
      getMethods.put(field.getName(), new GetFieldInvoker(field));
      Type fieldType = TypeParameterResolver.resolveFieldType(field, type);
      getTypes.put(field.getName(), typeToClass(fieldType));
    }
  }

  /**
   * 判断是否是合理的属性名
   * @param name
   * @return
   */
  private boolean isValidPropertyName(String name) {
    return !(name.startsWith("$") || "serialVersionUID".equals(name) || "class".equals(name));
  }

  /*
   * This method returns an array containing all methods
   * declared in this class and any superclass.
   * We use this method, instead of the simpler Class.getMethods(),
   * because we want to look for private methods as well.
   * 获得所有方法
   * @param cls The class
   * @return An array containing all methods in this class
   */
  private Method[] getClassMethods(Class<?> cls) {
	// 每个方法签名与该方法的映射
    Map<String, Method> uniqueMethods = new HashMap<String, Method>();
    // 循环类，类的父类，类的父类的父类，直到父类Object
    Class<?> currentClass = cls;
    while (currentClass != null && currentClass != Object.class) {
      // 记录当前类定义的方法
      addUniqueMethods(uniqueMethods, currentClass.getDeclaredMethods());

      // we also need to look for interface methods -
      // because the class may be abstract
      // 记录接口中定义的方法
      Class<?>[] interfaces = currentClass.getInterfaces();
      for (Class<?> anInterface : interfaces) {
        addUniqueMethods(uniqueMethods, anInterface.getMethods());
      }

      // 获得父类
      currentClass = currentClass.getSuperclass();
    }

    // 转换成Method数组返回
    Collection<Method> methods = uniqueMethods.values();

    return methods.toArray(new Method[methods.size()]);
  }

  private void addUniqueMethods(Map<String, Method> uniqueMethods, Method[] methods) {
    for (Method currentMethod : methods) {// 忽略bridge方法
      if (!currentMethod.isBridge()) {
    	// 获得方法签名
        String signature = getSignature(currentMethod);
        // check to see if the method is already known
        // if it is known, then an extended class must have
        // overridden a method
        // 当uniqueMethods中不存在此方法签名时，则进行添加
        if (!uniqueMethods.containsKey(signature)) {
          if (canAccessPrivateMethods()) {
            try {
              currentMethod.setAccessible(true);
            } catch (Exception e) {
              // Ignored. This is only a final precaution, nothing we can do.
            }
          }

          // 添加到uniqueMethods中
          uniqueMethods.put(signature, currentMethod);
        }
      }
    }
  }

  /**
   * 获得方法签名
   * @param method
   * @return
   */
  private String getSignature(Method method) {
    StringBuilder sb = new StringBuilder();
    // 返回类型
    Class<?> returnType = method.getReturnType();
    if (returnType != null) {
      sb.append(returnType.getName()).append('#');
    }
    // 方法名
    sb.append(method.getName());
    // 方法参数
    Class<?>[] parameters = method.getParameterTypes();
    for (int i = 0; i < parameters.length; i++) {
      if (i == 0) {
        sb.append(':');
      } else {
        sb.append(',');
      }
      sb.append(parameters[i].getName());
    }
    return sb.toString();
  }

  /**
   * 判断，是否可以修改可访问性
   * @return
   */
  private static boolean canAccessPrivateMethods() {
    try {
      SecurityManager securityManager = System.getSecurityManager();
      if (null != securityManager) {
        securityManager.checkPermission(new ReflectPermission("suppressAccessChecks"));
      }
    } catch (SecurityException e) {
      return false;
    }
    return true;
  }

  /*
   * Gets the name of the class the instance provides information for
   *
   * @return The class name
   */
  public Class<?> getType() {
    return type;
  }

  public Constructor<?> getDefaultConstructor() {
    if (defaultConstructor != null) {
      return defaultConstructor;
    } else {
      throw new ReflectionException("There is no default constructor for " + type);
    }
  }

  public boolean hasDefaultConstructor() {
    return defaultConstructor != null;
  }

  public Invoker getSetInvoker(String propertyName) {
    Invoker method = setMethods.get(propertyName);
    if (method == null) {
      throw new ReflectionException("There is no setter for property named '" + propertyName + "' in '" + type + "'");
    }
    return method;
  }

  public Invoker getGetInvoker(String propertyName) {
    Invoker method = getMethods.get(propertyName);
    if (method == null) {
      throw new ReflectionException("There is no getter for property named '" + propertyName + "' in '" + type + "'");
    }
    return method;
  }

  /*
   * Gets the type for a property setter
   *
   * @param propertyName - the name of the property
   * @return The Class of the propery setter
   */
  public Class<?> getSetterType(String propertyName) {
    Class<?> clazz = setTypes.get(propertyName);
    if (clazz == null) {
      throw new ReflectionException("There is no setter for property named '" + propertyName + "' in '" + type + "'");
    }
    return clazz;
  }

  /*
   * Gets the type for a property getter
   *
   * @param propertyName - the name of the property
   * @return The Class of the propery getter
   */
  public Class<?> getGetterType(String propertyName) {
    Class<?> clazz = getTypes.get(propertyName);
    if (clazz == null) {
      throw new ReflectionException("There is no getter for property named '" + propertyName + "' in '" + type + "'");
    }
    return clazz;
  }

  /*
   * Gets an array of the readable properties for an object
   *
   * @return The array
   */
  public String[] getGetablePropertyNames() {
    return readablePropertyNames;
  }

  /*
   * Gets an array of the writeable properties for an object
   *
   * @return The array
   */
  public String[] getSetablePropertyNames() {
    return writeablePropertyNames;
  }

  /*
   * Check to see if a class has a writeable property by name
   *
   * @param propertyName - the name of the property to check
   * @return True if the object has a writeable property by the name
   */
  public boolean hasSetter(String propertyName) {
    return setMethods.keySet().contains(propertyName);
  }

  /*
   * Check to see if a class has a readable property by name
   *
   * @param propertyName - the name of the property to check
   * @return True if the object has a readable property by the name
   */
  public boolean hasGetter(String propertyName) {
    return getMethods.keySet().contains(propertyName);
  }

  public String findPropertyName(String name) {
    return caseInsensitivePropertyMap.get(name.toUpperCase(Locale.ENGLISH));
  }
}
