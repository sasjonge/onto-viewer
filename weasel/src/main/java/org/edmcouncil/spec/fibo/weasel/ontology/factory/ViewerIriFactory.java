package org.edmcouncil.spec.fibo.weasel.ontology.factory;

import org.semanticweb.owlapi.model.IRI;

/**
 *
 * @author Michał Daniel (michal.daniel@makolab.com)
 */
public class ViewerIriFactory {

  private static final String IRI_FORMAT = "http://viewer.%s#%s";

  public enum Element {
    clazz, dataProperty, objectProperty, instance, empty
  }

  public enum Type {
    internal, external, axiom, function
  }

  /**
   * IRI creation using the pattern. This IRI is used in the "Fibo-viewer" functions to recognize
   * elements that are not represented by IRI. The pattern looks like http: //viewer.type#element.
   *
   * @param type Specific type
   * @param element Specific element
   * @return IRI created using parameters
   */
  public static IRI createIri(Type type, Element element) {
    if (element == Element.empty) {
      return IRI.create(String.format(IRI_FORMAT, type.name(), ""));
    }

    return IRI.create(String.format(IRI_FORMAT, type.name(), element.name()));
  }

   /**
   * IRI creation using the pattern. This IRI is used in the "Fibo-viewer" functions to recognize
   * elements that are not represented by IRI. The pattern looks like http: //viewer.type#element.
   *
   * @param type Specific type
   * @param element Specific element
   * @return IRI created using parameters
   */
  public static IRI createIri(Type type, String element) {
    if (element == null || element.isEmpty()) {
      return IRI.create(String.format(IRI_FORMAT, type.name(), ""));
    }

    return IRI.create(String.format(IRI_FORMAT, type.name(), element));
  }
}
