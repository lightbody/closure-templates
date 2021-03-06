/*
 * Copyright 2010 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.template.soy.parsepasses.contextautoesc;

import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.template.soy.base.IdGenerator;
import com.google.template.soy.shared.restricted.SoyPrintDirective;
import com.google.template.soy.soytree.CallNode;
import com.google.template.soy.soytree.PrintDirectiveNode;
import com.google.template.soy.soytree.PrintNode;
import com.google.template.soy.soytree.SoyFileSetNode;
import com.google.template.soy.soytree.SoyNode.StandaloneNode;
import com.google.template.soy.soytree.SoytreeUtils;
import com.google.template.soy.soytree.TemplateBasicNode;
import com.google.template.soy.soytree.TemplateDelegateNode;
import com.google.template.soy.soytree.TemplateNode;
import com.google.template.soy.soytree.TemplateNode.SoyFileHeaderInfo;

import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;


/**
 * Encapsulates information inferred about a Soy file and decisions made to change it.
 *
 * <p>
 * The mutator methods on this class do not change the Soy file.  All changes are delayed until
 * after all inference has been done so that we can safely try a variety of speculative techniques.
 *
 * <p>
 * To make it easier to do speculative inference, this class cascades : each instance has a parent,
 * and it delegates to that when it does not have info itself.
 * And there is a {@link Inferences#foldIntoParent()} method that propagates all decisions into the
 * parent when a set of inference decisions are considered final.
 *
 * <p>
 * The {@link ContextualAutoescaper} creates a single root instance and its passes fold successful
 * inferences into the parent until it ends up with a final set of rewriting decisions that the
 * {@link Rewriter} applies to the input Soy parse tree.
 *
 */
final class Inferences {

  /** Null or an instance to inherit state from. */
  private final @Nullable Inferences parent;

  /** Soy directives that {@link SoyPrintDirective#shouldCancelAutoescape cancel} autoescaping. */
  private final ImmutableSet<String> soyEscapingDirectives;

  /** Used to generate unique IDs for cloned templates. */
  private final IdGenerator idGen;

  /** Map of template names to instances used to type <code>{call}</code> commands. */
  private final Map<String, List<TemplateNode>> templatesByName = Maps.newLinkedHashMap();

  /** The types of templates. */
  private final Map<String, Context> templateNameToEndContext = Maps.newLinkedHashMap();

  /** Maps IDs of <code>{print}</code> commands to the inferred escaping modes. */
  private final Map<Integer, ImmutableList<EscapingMode>> idToEscapingModes =
      Maps.newLinkedHashMap();

  /** Maps IDs of <code>{call}</code> commands to the derived template they should use. */
  private final Map<Integer, String> callIdToDerivedCalleeName = Maps.newLinkedHashMap();

  /** The set of template names checked.  Used to identify re-entrant templates. */
  private final Set<String> templatesChecked = Sets.newHashSet();

  /**
   * An instance that inherits from a parent.
   */
  public Inferences(Inferences parent) {
    this.parent = parent;
    this.soyEscapingDirectives = parent.soyEscapingDirectives;
    this.idGen = parent.idGen;
  }

  /**
   * An instance that does not inherit from a parent.
   *
   * @param soyEscapingDirectives Soy directives that
   *     {@link SoyPrintDirective#shouldCancelAutoescape cancel} autoescaping.
   * @param idGen Used to generate unique IDs for cloned templates.
   * @param templatesByName Map of template names to instances used to type <code>{call}</code>
   *     commands.
   */
  public Inferences(
      Set<String> soyEscapingDirectives, IdGenerator idGen,
      Map<String, ImmutableList<TemplateNode>> templatesByName) {
    this.parent = null;
    this.soyEscapingDirectives = ImmutableSet.copyOf(soyEscapingDirectives);
    this.idGen = idGen;
    this.templatesByName.putAll(templatesByName);
  }

  /**
   * Stores a type conclusion.  This may be speculative.
   * @param templateName A qualified template name.
   */
  public void recordTemplateEndContext(String templateName, Context context) {
    templateNameToEndContext.put(templateName, context);
  }

  /**
   * Finds the named templates.
   * @param templateName A qualified template name.
   */
  public List<TemplateNode> lookupTemplates(String templateName) {
    for (Inferences inferences = this; inferences != null; inferences = inferences.parent) {
      List<TemplateNode> tn = inferences.templatesByName.get(templateName);
      if (tn != null) {
        return tn;
      }
    }
    return null;
  }

  /**
   * Null if no typing has been done for the named template, or otherwise the context after a call
   * to the named template.  Since we derive templates by start context at the call site, there
   * is no start context parameter.
   *
   * @param templateName A qualified template name.
   */
  public Context getTemplateEndContext(String templateName) {
    for (Inferences inferences = this; inferences != null; inferences = inferences.parent) {
      Context oc = inferences.templateNameToEndContext.get(templateName);
      if (oc != null) {
        return oc;
      }
    }
    return null;
  }

  /**
   * Null if there is no escaping mode for the given <code>{print}</code> command.
   */
  public ImmutableList<EscapingMode> getEscapingMode(PrintNode printNode) {
    // See if we have already inferred an escaping mode for printNode.
    int id = printNode.getId();
    for (Inferences inferences = this; inferences != null; inferences = inferences.parent) {
      ImmutableList<EscapingMode> escapingModes = inferences.idToEscapingModes.get(id);
      if (escapingModes != null) {
        return escapingModes;
      }
    }

    // Look for an escaping mode in the existing directives.
    ImmutableList.Builder<EscapingMode> modes = ImmutableList.builder();
    for (PrintDirectiveNode directive : printNode.getChildren()) {
      String directiveName = directive.getName();
      EscapingMode dirMode = EscapingMode.fromDirective(directiveName);
      if (dirMode != null) {
        modes.add(dirMode);
      } else if (soyEscapingDirectives.contains(directiveName)) {
        modes.add(EscapingMode.NO_AUTOESCAPE);
      }
    }
    return modes.build();
  }

  /**
   * Records inferred escaping modes so a directive can be later added to the Soy parse tree.
   */
  public void setEscapingDirectives(PrintNode printNode, List<EscapingMode> escapingModes) {
    idToEscapingModes.put(printNode.getId(), ImmutableList.copyOf(escapingModes));
  }

  /**
   * The escaping modes for the print command with the given ID in the order in which they should be
   * applied.
   * @param printId Like {@link PrintNode#getId}.
   */
  public ImmutableList<EscapingMode> getEscapingModesForId(int printId) {
    ImmutableList<EscapingMode> modes = idToEscapingModes.get(printId);
    if (modes == null) {
      modes = ImmutableList.of();
    }
    return modes;
  }

  /**
   * Derives a <code>{call}</code> site so that it uses a version of the template appropriate to
   * the start context.
   * @param derivedCalleeName A qualified template name.
   */
  public void retargetCall(CallNode cn, String derivedCalleeName) {
    callIdToDerivedCalleeName.put(cn.getId(), derivedCalleeName);
  }

  /**
   * The name of the derived template that the call with the given id should call, or null if the
   * call with the given id should not be retargeted to a derived template.
   */
  public String getDerivedCalleeNameForCallId(int callId) {
    return callIdToDerivedCalleeName.get(callId);
  }

  /**
   * Clones a template, changing the name.
   * @return A copy of tn, differing semantically only in name and auto-generated IDs.
   *     The new templates will be available via {@link #lookupTemplates} with the given name.
   */
  public List<TemplateNode> cloneTemplates(String baseName, String derivedName) {
    if (lookupTemplates(derivedName) != null) {
      throw new AssertionError(derivedName);
    }

    ImmutableList.Builder<TemplateNode> b = ImmutableList.builder();

    for (TemplateNode tn : lookupTemplates(baseName)) {
      SoyFileHeaderInfo soyFileHeaderInfo = tn.getSoyFileHeaderInfo();

      int cloneId = tn.getNearestAncestor(SoyFileSetNode.class).getNodeIdGenerator().genId();

      // We need to use the unnamespaced name in the command text since we'll be inserting this
      // template into a file node that already has a namespace declaration.
      TemplateNode clone;
      boolean useAttrStyleForName = tn.getCommandText().contains("name=");
      if (tn instanceof TemplateBasicNode) {
        TemplateBasicNode tbn = (TemplateBasicNode) tn;

        String derivedPartialName = (tn.getPartialTemplateName() != null) ?
            derivedName.substring(soyFileHeaderInfo.namespace.length()) : null;

        clone = new TemplateBasicNode(
            cloneId, soyFileHeaderInfo, derivedName, derivedPartialName,
            useAttrStyleForName, tbn.isOverride(), tn.isPrivate(),
            tn.getAutoescapeMode(), tn.getSoyDoc(), tn.getSyntaxVersion());

        if (! (derivedName.equals(clone.getTemplateName()) &&
            Objects.equal(derivedPartialName, clone.getPartialTemplateName()))) {
          throw new AssertionError();
        }
      } else if (tn instanceof TemplateDelegateNode) {
        TemplateDelegateNode tdn = (TemplateDelegateNode) tn;
        clone = new TemplateDelegateNode(
            cloneId, soyFileHeaderInfo, derivedName, tdn.getDelPriority(),
            tn.getAutoescapeMode(), tn.getSoyDoc());

        if (! (derivedName.equals(((TemplateDelegateNode) clone).getDelTemplateName()))) {
          throw new AssertionError();
        }
      } else {
        throw new AssertionError("Unknown template node type: " + tn.getClass());
      }

      clone.setLocation(tn.getLocation());

      for (StandaloneNode child : tn.getChildren()) {
        clone.addChild(SoytreeUtils.cloneWithNewIds(child));
      }

      b.add(clone);
    }

    ImmutableList<TemplateNode> clones = b.build();
    templatesByName.put(derivedName, clones);
    return clones;
  }

  /**
   * Folds speculative decisions into the parent passed to the constructor.
   * This instance should not be used after folding.
   */
  public void foldIntoParent() {
    parent.idToEscapingModes.putAll(idToEscapingModes);
    parent.templateNameToEndContext.putAll(templateNameToEndContext);
    parent.callIdToDerivedCalleeName.putAll(callIdToDerivedCalleeName);
    parent.templatesByName.putAll(templatesByName);
    parent.templatesChecked.addAll(templatesChecked);
  }

  /**
   * All known templates.
   */
  public List<TemplateNode> getAllTemplates() {
    ImmutableList.Builder<TemplateNode> b = ImmutableList.builder();
    for (List<TemplateNode> templates : templatesByName.values()) {
      b.addAll(templates);
    }
    return b.build();
  }

  /**
   * Indicates that a template was visited.
   * @see #wasTemplateChecked
   */
  public void recordTemplateChecked(String templateName) {
    templatesChecked.add(templateName);
  }

  /**
   * True if {@link #recordTemplateChecked} was called with the same template name.
   */
  public boolean wasTemplateChecked(String templateName) {
    return templatesChecked.contains(templateName);
  }

  /**
   * The id generator used for newly created nodes.
   */
  public IdGenerator getIdGenerator() {
    return idGen;
  }
}
