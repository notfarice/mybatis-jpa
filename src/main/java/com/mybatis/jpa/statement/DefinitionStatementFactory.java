package com.mybatis.jpa.statement;

import com.mybatis.jpa.definition.AnnotationDefinitionRegistry;
import com.mybatis.jpa.definition.adaptor.AnnotationAdaptable;
import com.mybatis.jpa.definition.property.AnnotationProperty;
import com.mybatis.jpa.definition.template.SqlTemplate;
import com.mybatis.jpa.util.PersistentUtil;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import org.apache.ibatis.executor.keygen.Jdbc3KeyGenerator;
import org.apache.ibatis.executor.keygen.KeyGenerator;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.mapping.StatementType;
import org.apache.ibatis.scripting.LanguageDriver;
import org.apache.ibatis.session.Configuration;
import sun.reflect.generics.reflectiveObjects.TypeVariableImpl;

/**
 * @author sway.li
 **/
public class DefinitionStatementFactory implements StatementFactory {

  protected Configuration configuration;

  protected AnnotationDefinitionRegistry definitionRegistry;

  public DefinitionStatementFactory(Configuration configuration) {
    this(configuration, new AnnotationDefinitionRegistry());
  }

  public DefinitionStatementFactory(Configuration configuration,
      AnnotationDefinitionRegistry definitionRegistry) {
    this.configuration = configuration;
    this.definitionRegistry = definitionRegistry;
  }

  @Override
  public void parseStatement(Method method, Class<?> targetClass) {
    String resourceName = targetClass.toString();

    if (!configuration.isResourceLoaded(resourceName)) {
      configuration.addLoadedResource(resourceName);
    }
    String targetClassName = targetClass.getName();

    LanguageDriver languageDriver = configuration.getDefaultScriptingLanuageInstance();
    SqlSource sqlSource = languageDriver
        .createSqlSource(configuration, parseSQL(method, targetClass), Object.class);
    String statementId = targetClassName + "." + method.getName();
    SqlCommandType sqlCommandType = recognizeSqlCommandType(method);
    MappedStatement.Builder builder = new MappedStatement.Builder(configuration, statementId,
        sqlSource, sqlCommandType);
    String resource = recognizeResource(targetClassName);
    builder.resource(resource).lang(languageDriver).statementType(StatementType.PREPARED);

    // keyGenerator
    if (SqlCommandType.INSERT.equals(sqlCommandType)) {
      Class<?> type = recognizeEntityType(method, targetClass);
      Field id = PersistentUtil.getIdField(type);
      if (id.isAnnotationPresent(GeneratedValue.class)) {
        GeneratedValue generatedValue = id.getAnnotation(GeneratedValue.class);
        if (GenerationType.AUTO.equals(generatedValue.strategy())) {
          builder.keyGenerator(new Jdbc3KeyGenerator()).keyProperty(id.getName())
              .keyColumn(PersistentUtil.getColumnName(id));
        } else if (GenerationType.IDENTITY.equals(generatedValue.strategy())) {
          String generator = "".equals(generatedValue.generator()) ? "defaultKeyGenerator"
              : generatedValue.generator();
          KeyGenerator keyGenerator = configuration.getKeyGenerator(generator);
          if (keyGenerator == null) {
            throw new IllegalArgumentException(
                "mybatis jpa init failure , can not find " + generator + " in configuration.");
          }
          builder.keyGenerator(keyGenerator).keyProperty(id.getName())
              .keyColumn(PersistentUtil.getColumnName(id));
        }
      }
    }
    MappedStatement statement = builder.build();
    configuration.addMappedStatement(statement);
  }

  protected String parseSQL(Method method, Class<?> targetClass) {
    Annotation annotation = recognizeDefinitionAnnotation(method);
    AnnotationAdaptable adaptor = recognizeAdaptor(method);

    AnnotationProperty annotationProperty = adaptor.context(annotation);
    SqlTemplate sqlTemplate = adaptor.sqlTemplate(annotation);

    Class<?> type = recognizeEntityType(method, targetClass);

    String sql = sqlTemplate.parseSQL(type);

    if (SqlCommandType.UPDATE.equals(adaptor.sqlCommandType()) || SqlCommandType.DELETE
        .equals(adaptor.sqlCommandType())) {
      if (!"".equals(annotationProperty.where())) {
        sql = sql + " where " + annotationProperty.where();
      }
    }

    return "<script> " + sql + "</script>";
  }

  protected String recognizeResource(String targetClassName) {
    return targetClassName.replace(".", "/") + ".java (best guess)";
  }

  protected SqlCommandType recognizeSqlCommandType(Method method) {
    return recognizeAdaptor(method).sqlCommandType();
  }

  protected AnnotationAdaptable recognizeAdaptor(Method method) {
    Annotation annotation = recognizeDefinitionAnnotation(method);
    return definitionRegistry.resolveAdaptor(annotation.annotationType());
  }

  protected Annotation recognizeDefinitionAnnotation(Method method) {
    for (Class<? extends Annotation> annotationType : definitionRegistry.getAnnotationAdaptors()
        .keySet()) {
      if (method.isAnnotationPresent(annotationType)) {
        return method.getAnnotation(annotationType);
      }
    }
    return null;
  }

  protected Class<?> recognizeEntityType(Method method, Class<?> targetClass) {
    Type[] genericTypes = method.getGenericParameterTypes();
    Type genericType = genericTypes[0];
    if (genericType instanceof TypeVariableImpl) {
      // interface XXXMapper extends IBaseMapper<T>
      Type[] interfaces = targetClass.getGenericInterfaces();
      for (Type type : interfaces) {
        if (type instanceof ParameterizedType) {
          ParameterizedType pt = (ParameterizedType) type;
          return (Class<?>) pt.getActualTypeArguments()[0];
        }
      }
    }
    Class<?> actualType;
    if (genericType instanceof ParameterizedType) {
      ParameterizedType pt = (ParameterizedType) genericType;
      actualType = (Class<?>) pt.getActualTypeArguments()[0];
    } else {
      actualType = (Class<?>) genericType;
    }
    return actualType;
  }

}