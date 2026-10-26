package net.kuching.cattail.deploy;

/** Lazy holder for {@link Deployment#bundled()}: the jar's deployment file is read once, on first use. */
final class BundledDeployment {

    static final Deployment VALUE = Deployment.load(Deployment.class.getClassLoader());

    private BundledDeployment() {}
}
