/**
 * JAXB mirrors of the fiscal domain, one package per Hacienda document type.
 *
 * <p><b>Why seven near-identical packages exist — and why they must stay
 * separate.</b> {@code FE}, {@code FEC}, {@code FEE}, {@code NC}, {@code ND},
 * {@code REP} and {@code TE} look like copy-paste: ~188 of their files are
 * byte-identical once the {@code package} line and the namespace are
 * normalized. That duplication is load-bearing, not laziness. JAXB resolves an
 * element's namespace from its class's package default
 * ({@code package-info.java}) unless the mapping carries an explicit one, and
 * namespaces do NOT inherit from parent elements. The shared classes
 * ({@code Emisor}, {@code LineaDetalle}, ...) deliberately carry NO explicit
 * namespace — they take their document's namespace from the package they sit
 * in. Moving them into one shared package would strip every nested element of
 * its namespace and produce XML the Hacienda XSDs reject. There is no global
 * prefix mapper and marshalling is a plain {@code marshaller.marshal(doc, sw)}
 * in the seven {@code Services.Strategies.*} classes, so nothing else supplies
 * it. A consolidation was measured and rejected on these grounds; do not
 * re-attempt it without re-proving namespace-qualified output per document
 * (the gate is {@code Documentos.XsdModelAlignmentTest} plus a signed round
 * trip per type).</p>
 *
 * <p><b>What IS shared and what diverges.</b> Only these files differ between
 * documents for reasons beyond the namespace, and only they are kept
 * per-document alongside each root {@code *Documento} and each
 * {@code Encabezado} (whose per-element namespace attributes ARE the
 * document's identity and must stay): {@code FEE.Exoneracion} (different field
 * names: {@code TipoDocumento}/{@code FechaEmision}/
 * {@code PorcentajeExoneracion}, no {@code TipoDocumentoOTRO}/{@code Articulo}/
 * {@code Inciso}/{@code NombreInstitucionOtros}), {@code REP.Emisor} (minimal:
 * most fields nulled per its XSD), {@code REP.Impuesto} (no exoneration),
 * {@code REP.ResumenFactura} (explicit {@code propOrder}). Everything else
 * exists once per document only because the namespace forces it.</p>
 *
 * <p><b>Copy-constructor discipline.</b> Every class except the seven roots
 * carries exactly one {@code public X(Models.…​.X src)} constructor that copies
 * the domain object field by field. That list rots silently: adding a field to
 * the domain without extending the mirror's constructor drops data from every
 * signed document with no compiler error. {@code JaxbCopiaCompletaTest} pins
 * the round trip for the fiscal core — extend it when the domain grows. (A
 * reflection copier for this, {@code JaxbCopier}, was written, never adopted,
 * and removed: silent reflection drops are worse than explicit lists guarded
 * by a test.)</p>
 */
package Models.Jaxb;
