package play.modules.guice;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import play.Logger;
import play.Play;
import play.PlayPlugin;
import play.inject.BeanSource;

import com.google.inject.AbstractModule;
import com.google.inject.BindingAnnotation;
import com.google.inject.ConfigurationException;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.Module;
import com.google.inject.name.Named;
/**
 *  Enable <a href="http://google-guice.googlecode.com">Guice</a> integration
 *  in Playframework.
 *  This plugin first scans for a custom Guice Injector if it's not found, then
 *  it tries to create an injector from all the guice modules available on the classpath.
 *  The Plugin is then passed to Play injector for Controller IoC.
 *
 *  @author <a href="mailto:info@lucianofiandesio.com">Luciano Fiandesio</a>
 *  @author <a href="mailto:info@hausel@freemail.hu">Peter Hausel</a>
 */
public class GuicePlugin extends PlayPlugin implements BeanSource {

	
    Injector injector;

    @Override
    public void onApplicationStart() {
        
        final List<Module> modules = new ArrayList<Module>();
        final List<Class> ll = Play.classloader.getAllClasses();
        Logger.debug("Starting Guice modules scanning");
		Boolean newInjectorNeeded = true;
		StringBuffer moduleList = new StringBuffer();
        for (final Class clz : ll) {
        	//first check if there is a custom Injector on the classpath, if so, stop scanning 
			//and ignore modules altogether
			if (clz.getSuperclass() != null && GuiceSupport.class.isAssignableFrom(clz)) {
               try {
			       GuiceSupport gs = (GuiceSupport) clz.newInstance();
			       this.injector = gs.configure();
			       newInjectorNeeded = false;
               	   Logger.info("Guice injector was found: " + clz.getName());
			   	   break;
			   } catch (Exception e) {
				    e.printStackTrace();
					throw new IllegalStateException("Unable to create Guice Injector for " + clz.getName());
			   }
			}
            if (clz.getSuperclass() != null && AbstractModule.class.isAssignableFrom(clz)) {
                try {
                    modules.add((Module) clz.newInstance());
                	moduleList.append(clz.getName()+" ");
                } catch (Exception e) {
					e.printStackTrace();
					throw new IllegalStateException("Unable to create Guice module for " + clz.getName());
                }
            }


        }
		if (newInjectorNeeded && modules.isEmpty()) {
			 throw new IllegalStateException("could not find any custom guice injector or abstract modules. Are you sure you have at least one on the classpath?");
		}
        if (!modules.isEmpty() && newInjectorNeeded) {
			Logger.info("Guice modules were found: "+moduleList);
            this.injector = Guice.createInjector(modules);
        } 
        // play inject Controller/Job/Mail only at the moment
        play.inject.Injector.inject(this);
        
        // let's inject other classes with play.modules.guice.InjectSupport annotation
        injectAnnotated(this);
    }

    public <T> T getBeanOfType(Class<T> clazz) {
        if (this.injector==null)return null;
        T bean = null;
        try{
        	bean = this.injector.getInstance(clazz);
        }
        catch(ConfigurationException ex){
        	Logger.error(ex.getMessage());
        }
        
        return bean;
    }
    
    public <T> T getBeanWithKey(Key<T> key){
    	if (this.injector==null)return null;
        return this.injector.getInstance(key);
    }
    
    private void injectAnnotated(BeanSource source) {
        List<Class> classes = Play.classloader.getAnnotatedClasses(play.modules.guice.InjectSupport.class);
        for(Class<?> clazz : classes) {
            for(Field field : clazz.getDeclaredFields()) {
                if(Modifier.isStatic(field.getModifiers()) && field.isAnnotationPresent(Inject.class)) {
                    Class<?> type = field.getType();
                    field.setAccessible(true);
                    
                    try {
                    	// for guice binding annotations to work, we must inspect the field's annotations
	                	Annotation [] annotations = field.getAnnotations();
	                	Annotation bindingAnnotation = null;
	                	for (Annotation annotation : annotations){
	                		
	                		// field uses guice's built in Named annotation
	                		if (annotation.annotationType().equals(Named.class)){
	                			bindingAnnotation = annotation;
	                		}
	                		
	                		// check for custom annotations marked with guice's BindingAnnotation
	                		Annotation [] internalAnnotations = annotation.annotationType().getAnnotations();
	                		for (Annotation internal : internalAnnotations){
		                		if (internal.annotationType().equals(BindingAnnotation.class)){
		                			bindingAnnotation = annotation;
		                		}
	                		}
	                	}
	                	
	                	// if we found a bindingAnnotation, then fetch the bounded bean with a key
	                	if (bindingAnnotation != null){
	                		Key<?> key = Key.get(type, bindingAnnotation);
	                		field.set(null, getBeanWithKey(key));
	                	}
	                	else{
	                		field.set(null, source.getBeanOfType(type));
	                	}
                    } catch(RuntimeException e) {
                        throw e;
                    } catch(Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        }
    }
}
