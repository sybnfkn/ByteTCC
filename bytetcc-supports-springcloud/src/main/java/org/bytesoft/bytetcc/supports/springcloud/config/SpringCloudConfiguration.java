/**
 * Copyright 2014-2017 yangming.liu<bytefox@126.com>.
 *
 * This copyrighted material is made available to anyone wishing to use, modify,
 * copy, or redistribute it subject to the terms and conditions of the GNU
 * Lesser General Public License, as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this distribution; if not, see <http://www.gnu.org/licenses/>.
 */
package org.bytesoft.bytetcc.supports.springcloud.config;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.bytesoft.bytetcc.TransactionManagerImpl;
import org.bytesoft.bytetcc.UserCompensableImpl;
import org.bytesoft.bytetcc.supports.spring.SpringContextRegistry;
import org.bytesoft.bytetcc.supports.springcloud.SpringCloudBeanRegistry;
import org.bytesoft.bytetcc.supports.springcloud.feign.CompensableClientRegistry;
import org.bytesoft.bytetcc.supports.springcloud.feign.CompensableFeignBeanPostProcessor;
import org.bytesoft.bytetcc.supports.springcloud.feign.CompensableFeignContract;
import org.bytesoft.bytetcc.supports.springcloud.feign.CompensableFeignDecoder;
import org.bytesoft.bytetcc.supports.springcloud.feign.CompensableFeignErrorDecoder;
import org.bytesoft.bytetcc.supports.springcloud.feign.CompensableFeignInterceptor;
import org.bytesoft.bytetcc.supports.springcloud.hystrix.CompensableHystrixBeanPostProcessor;
import org.bytesoft.bytetcc.supports.springcloud.loadbalancer.CompensableLoadBalancerRuleImpl;
import org.bytesoft.bytetcc.supports.springcloud.property.CompensablePropertySourceFactory;
import org.bytesoft.bytetcc.supports.springcloud.web.CompensableHandlerInterceptor;
import org.bytesoft.bytetcc.supports.springcloud.web.CompensableRequestInterceptor;
import org.bytesoft.bytetcc.supports.svc.mvc.ServiceErrorResolver;
import org.bytesoft.common.utils.CommonUtils;
import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.aware.CompensableBeanFactoryAware;
import org.bytesoft.compensable.aware.CompensableEndpointAware;
import org.springframework.beans.BeansException;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.beans.PropertyValue;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.http.HttpMessageConverters;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.ImportResource;
import org.springframework.context.annotation.PropertySource;
import org.springframework.core.env.Environment;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.TransactionManagementConfigurer;
import org.springframework.transaction.jta.JtaTransactionManager;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.mongodb.ServerAddress;
import com.mongodb.client.MongoClients;

@PropertySource(value = "bytetcc:loadbalancer.config", factory = CompensablePropertySourceFactory.class)
@ImportResource({ "classpath:bytetcc-supports-springcloud.xml" })
@EnableAspectJAutoProxy(proxyTargetClass = true)
@EnableTransactionManagement
public class SpringCloudConfiguration implements TransactionManagementConfigurer, WebMvcConfigurer,
		BeanFactoryPostProcessor, InitializingBean, SmartInitializingSingleton, CompensableEndpointAware,
		CompensableBeanFactoryAware, EnvironmentAware, ApplicationContextAware {
	static final String CONSTANT_INCLUSIONS = "org.bytesoft.bytetcc.feign.inclusions";
	static final String CONSTANT_EXCLUSIONS = "org.bytesoft.bytetcc.feign.exclusions";
	static final String FEIGN_FACTORY_CLASS = "org.springframework.cloud.openfeign.FeignClientFactoryBean";

	static final String CONSTANT_MONGODBURI = "spring.data.mongodb.uri";

	private ApplicationContext applicationContext;
	private String identifier;
	private Environment environment;
	private CompensableBeanFactory beanFactory;
	private transient final Set<String> transientClientSet = new HashSet<String>();

	private void checkLoadbalancerRuleCorrectly() /* Check if the rule is set correctly */ {
		com.netflix.loadbalancer.IRule loadBalancerRule = null;
		try {
			loadBalancerRule = this.applicationContext.getBean(com.netflix.loadbalancer.IRule.class);
		} catch (NoSuchBeanDefinitionException ex) {
			return; // return quietly
		}

		if (CompensableLoadBalancerRuleImpl.class.isInstance(loadBalancerRule)) {
			return; // return quietly
		}

		throw new IllegalStateException("CompensableLoadBalancerRuleImpl is disabled!");
	}

	@Override
	public void afterSingletonsInstantiated() {
		this.checkLoadbalancerRuleCorrectly();
	}

	@Override
	public void afterPropertiesSet() throws Exception {
		String host = CommonUtils.getInetAddress();
		String name = this.environment.getProperty("spring.application.name");
		String port = this.environment.getProperty("server.port");
		// 标示服务
		this.identifier = String.format("%s:%s:%s", host, name, port);
	}

	// <!-- <bean id="jtaTransactionManager"
	// class="org.springframework.transaction.jta.JtaTransactionManager"> -->
	// <!-- <property name="userTransaction" ref="bytetccUserTransaction" /> -->
	// <!-- <property name="transactionManager" ref="transactionManager" /> -->
	// <!-- </bean> -->
	// <!-- <tx:annotation-driven transaction-manager="jtaTransactionManager" /> -->
	@Override
	public PlatformTransactionManager annotationDrivenTransactionManager() {
		JtaTransactionManager jtaTransactionManager = new JtaTransactionManager();
		jtaTransactionManager.setTransactionManager(this.applicationContext.getBean(TransactionManagerImpl.class));
		jtaTransactionManager.setUserTransaction(this.applicationContext.getBean(UserCompensableImpl.class));

		SpringContextRegistry springContextRegistry = SpringContextRegistry.getInstance();
		springContextRegistry.setApplicationContext(this.applicationContext);
		springContextRegistry.setBeanFactory(this.beanFactory);
		springContextRegistry.setTransactionManager(jtaTransactionManager);
		return springContextRegistry.getTransactionManager();
	}

	@org.springframework.context.annotation.Bean("jtaTransactionManager")
	public PlatformTransactionManager jtaTransactionManager() {
		return SpringContextRegistry.getInstance().getTransactionManager();
	}

	@ConditionalOnMissingBean(com.mongodb.client.MongoClient.class)
	@ConditionalOnProperty(CONSTANT_MONGODBURI)
	@org.springframework.context.annotation.Bean
	public com.mongodb.client.MongoClient mongoClient(
			@Autowired(required = false) com.mongodb.MongoClient mongoClient) {
		if (mongoClient == null) {
			return MongoClients.create(this.environment.getProperty(CONSTANT_MONGODBURI));
		} else {
			List<ServerAddress> addressList = mongoClient.getAllAddress();
			StringBuilder ber = new StringBuilder();
			for (int i = 0; addressList != null && i < addressList.size(); i++) {
				ServerAddress address = addressList.get(i);
				String host = address.getHost();
				int port = address.getPort();
				if (i == 0) {
					ber.append(host).append(":").append(port);
				} else {
					ber.append(",").append(host).append(":").append(port);
				}
			}
			return MongoClients.create(String.format("mongodb://%s", ber.toString()));
		}
	}

	@org.springframework.context.annotation.Bean
	@ConditionalOnProperty(name = "feign.hystrix.enabled", havingValue = "false", matchIfMissing = true)
	public CompensableFeignBeanPostProcessor feignPostProcessor() {
		return new CompensableFeignBeanPostProcessor();
	}

	@org.springframework.context.annotation.Bean
	@ConditionalOnProperty(name = "feign.hystrix.enabled")
	@ConditionalOnClass(feign.hystrix.HystrixFeign.class)
	public CompensableHystrixBeanPostProcessor hystrixPostProcessor() {
		return new CompensableHystrixBeanPostProcessor();
	}

	@org.springframework.context.annotation.Bean
	public CompensableFeignInterceptor compensableFeignInterceptor() {
		CompensableFeignInterceptor interceptor = new CompensableFeignInterceptor();
		interceptor.setEndpoint(this.identifier);
		return interceptor;
	}

	@org.springframework.context.annotation.Primary
	@org.springframework.context.annotation.Bean
	public feign.Contract compensableFeignContract() {
		return new CompensableFeignContract();
	}

	@org.springframework.context.annotation.Primary
	@org.springframework.context.annotation.Bean
	public feign.codec.Decoder compensableFeignDecoder(@Autowired ObjectFactory<HttpMessageConverters> objectFactory) {
		CompensableFeignDecoder feignDecoder = new CompensableFeignDecoder();
		feignDecoder.setObjectFactory(objectFactory);
		return feignDecoder;
	}

	@org.springframework.context.annotation.Primary
	@org.springframework.context.annotation.Bean
	public feign.codec.ErrorDecoder compensableErrorDecoder() {
		return new CompensableFeignErrorDecoder();
	}

	@org.springframework.context.annotation.Bean
	public CompensableHandlerInterceptor compensableHandlerInterceptor() {
		CompensableHandlerInterceptor interceptor = new CompensableHandlerInterceptor();
		interceptor.setEndpoint(this.identifier);
		return interceptor;
	}

	@org.springframework.context.annotation.Bean
	public CompensableRequestInterceptor compensableRequestInterceptor() {
		CompensableRequestInterceptor interceptor = new CompensableRequestInterceptor();
		interceptor.setEndpoint(this.identifier);
		return interceptor;
	}

	@SuppressWarnings("deprecation")
	@org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(ClientHttpRequestFactory.class)
	@org.springframework.context.annotation.Bean
	public ClientHttpRequestFactory defaultRequestFactory() {
		return new org.springframework.http.client.Netty4ClientHttpRequestFactory();
	}

	@org.springframework.context.annotation.Bean("compensableRestTemplate")
	@org.springframework.cloud.client.loadbalancer.LoadBalanced
	public RestTemplate transactionTemplate(@Autowired ClientHttpRequestFactory requestFactory) {
		RestTemplate restTemplate = new RestTemplate();
		restTemplate.setRequestFactory(requestFactory);

		SpringCloudBeanRegistry registry = SpringCloudBeanRegistry.getInstance();
		registry.setRestTemplate(restTemplate);

		return restTemplate;
	}

	@org.springframework.context.annotation.Primary
	@org.springframework.cloud.client.loadbalancer.LoadBalanced
	@org.springframework.context.annotation.Bean
	public RestTemplate defaultRestTemplate(@Autowired CompensableRequestInterceptor compensableRequestInterceptor) {
		RestTemplate restTemplate = new RestTemplate();
		restTemplate.getInterceptors().add(compensableRequestInterceptor);
		return restTemplate;
	}

	public void addInterceptors(InterceptorRegistry registry) {
		CompensableHandlerInterceptor compensableHandlerInterceptor = this.applicationContext
				.getBean(CompensableHandlerInterceptor.class);
		registry.addInterceptor(compensableHandlerInterceptor);
	}

	@Override
	public void configureHandlerExceptionResolvers(List<HandlerExceptionResolver> resolvers) {
		resolvers.add(new ServiceErrorResolver());
	}

	@Override
	public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {

		String[] beanNameArray = beanFactory.getBeanDefinitionNames();
		for (int i = 0; i < beanNameArray.length; i++) {
			BeanDefinition beanDef = beanFactory.getBeanDefinition(beanNameArray[i]);
			String beanClassName = beanDef.getBeanClassName();

			if (FEIGN_FACTORY_CLASS.equals(beanClassName) == false) {
				continue;
			}

			MutablePropertyValues mpv = beanDef.getPropertyValues();
			PropertyValue pv = mpv.getPropertyValue("name");
			String client = String.valueOf(pv.getValue() == null ? "" : pv.getValue());
			if (StringUtils.isNotBlank(client)) {
				this.transientClientSet.add(client);
			}

		}

		this.fireAfterPropertiesSet();

	}

	public void fireAfterPropertiesSet() {
		CompensableClientRegistry registry = CompensableClientRegistry.getInstance();

		String inclusions = this.environment.getProperty(CONSTANT_INCLUSIONS);
		String exclusions = this.environment.getProperty(CONSTANT_EXCLUSIONS);

		if (StringUtils.isNotBlank(inclusions) && StringUtils.isNotBlank(exclusions)) {
			throw new IllegalStateException(String.format("Property '%s' and '%s' can not be configured together!",
					CONSTANT_INCLUSIONS, CONSTANT_EXCLUSIONS));
		} else if (StringUtils.isNotBlank(inclusions)) {
			String[] clients = inclusions.split("\\s*,\\s*");
			for (int i = 0; i < clients.length; i++) {
				String client = clients[i];
				registry.registerClient(client);
			} // end-for (int i = 0; i < clients.length; i++)

			this.transientClientSet.clear();
		} else if (StringUtils.isNotBlank(exclusions)) {
			String[] clients = exclusions.split("\\s*,\\s*");
			for (int i = 0; i < clients.length; i++) {
				String client = clients[i];
				this.transientClientSet.remove(client);
			} // end-for (int i = 0; i < clients.length; i++)

			Iterator<String> itr = this.transientClientSet.iterator();
			while (itr.hasNext()) {
				String client = itr.next();
				itr.remove();
				registry.registerClient(client);
			} // end-while (itr.hasNext())
		} else {
			Iterator<String> itr = this.transientClientSet.iterator();
			while (itr.hasNext()) {
				String client = itr.next();
				itr.remove();
				registry.registerClient(client);
			} // end-while (itr.hasNext())
		}

	}

	// public void onApplicationEvent(ApplicationReadyEvent event) {}
	@Override
	public String getEndpoint() {
		return this.identifier;
	}
	@Override
	public CompensableBeanFactory getBeanFactory() {
		return this.beanFactory;
	}
	@Override
	public void setBeanFactory(CompensableBeanFactory tbf) {
		this.beanFactory = tbf;
	}
	@Override
	public void setEndpoint(String identifier) {
		this.identifier = identifier;
	}
	@Override
	public void setEnvironment(Environment environment) {
		this.environment = environment;
	}
	@Override
	public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
		this.applicationContext = applicationContext;
	}

}
