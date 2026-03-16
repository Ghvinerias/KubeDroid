package com.kubedroid.app.navigation

/** App entry and top-level destination routes. */
object Routes {
    object EntryGate {
        const val root = "entry_gate"
    }

    object Onboarding {
        const val root = "onboarding"
    }

    object Dashboard {
        const val root = "dashboard"
    }

    object AddCluster {
        const val root = "add_cluster"
    }

    object Pods {
        const val list = "pods"
        const val resourceDetail = "resource_detail/{kind}/{namespace}/{name}"
        const val resourceYamlEditor = "resource_yaml_editor/{kind}/{namespace}/{name}"
        const val logs = "pod_logs/{api}/{namespace}/{name}"
        const val exec = "pod_exec/{api}/{namespace}/{name}"
        const val portForward = "pod_port_forward/{api}/{namespace}/{name}"
    }

    object Crds {
        const val list = "crds"
        const val resources = "crd_resources/{name}/{group}/{version}/{scope}/{kind}"
        const val resourceDetail = "crd_resource_detail/{name}/{group}/{version}/{scope}/{kind}/{resourceNamespace}/{resourceName}"
    }

    object Deployments {
        const val list = "deployments"
        const val detail = "deployment_detail/{namespace}/{name}"
    }

    object Events {
        const val list = "events"
    }

    object Helm {
        const val list = "helm"
    }

    object Nodes {
        const val list = "nodes"
        const val detail = "node_detail/{name}"
    }

    object Network {
        const val list = "network"
        const val ingressDetail = "ingress_detail/{namespace}/{ingressName}"
    }

    object Rbac {
        const val list = "rbac"
    }

    object Storage {
        const val list = "storage"
        const val pvcDetail = "storage_pvc_detail/{namespace}/{name}"
        const val configMapDetail = "storage_config_map_detail/{namespace}/{name}"
        const val secretDetail = "storage_secret_detail/{namespace}/{name}"
    }

    object Settings {
        const val root = "settings"
        const val deployManifest = "deploy_manifest"
    }

    const val ENTRY_GATE = EntryGate.root
    const val ONBOARDING = Onboarding.root
    const val DASHBOARD = Dashboard.root
    const val ADD_CLUSTER = AddCluster.root
    const val PODS = Pods.list
    const val CRDS = Crds.list
    const val DEPLOYMENTS = Deployments.list
    const val EVENTS = Events.list
    const val HELM = Helm.list
    const val NODES = Nodes.list
    const val NETWORK = Network.list
    const val RBAC = Rbac.list
    const val STORAGE = Storage.list
    const val SETTINGS = Settings.root
    const val DEPLOY_MANIFEST = Settings.deployManifest

    const val CRD_RESOURCES = Crds.resources
    const val CRD_RESOURCE_DETAIL = Crds.resourceDetail

    const val RESOURCE_DETAIL = Pods.resourceDetail
    const val RESOURCE_YAML_EDITOR = Pods.resourceYamlEditor

    const val DEPLOYMENT_DETAIL = Deployments.detail
    const val NODE_DETAIL = Nodes.detail
    const val INGRESS_DETAIL = Network.ingressDetail

    const val PVC_DETAIL = Storage.pvcDetail
    const val CONFIG_MAP_DETAIL = Storage.configMapDetail
    const val SECRET_DETAIL = Storage.secretDetail

    const val POD_LOGS = Pods.logs
    const val POD_EXEC = Pods.exec
    const val POD_PORT_FORWARD = Pods.portForward
}
