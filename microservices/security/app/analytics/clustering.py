from dataclasses import dataclass
import numpy as np
from sklearn.cluster import KMeans
from sklearn.metrics import silhouette_score
from sklearn.preprocessing import StandardScaler


@dataclass
class Prediction:
    cluster: int
    distance: float
    threshold: float
    model_version: int | None = None
    @property
    def anomalous(self): return self.distance > self.threshold
    @property
    def distance_ratio(self):
        return self.distance / self.threshold if self.threshold > 0 else (float("inf") if self.distance > 0 else 0.0)


class TopicModel:
    def __init__(self, min_clusters=2, max_clusters=6, percentile=95):
        self.min_clusters=min_clusters; self.max_clusters=max_clusters; self.percentile=percentile
        self.scaler=StandardScaler(); self.model=None; self.thresholds={}; self.score=None
    def fit(self, rows):
        x=np.asarray(rows,dtype=float); z=self.scaler.fit_transform(x)
        unique=len(np.unique(np.round(z,10),axis=0))
        if unique < 2:
            self.model=KMeans(n_clusters=1,random_state=42,n_init=10).fit(z); self.score=None
        else:
            candidates=[]
            for k in range(self.min_clusters,min(self.max_clusters,len(z)-1,unique)+1):
                m=KMeans(n_clusters=k,random_state=42,n_init=10).fit(z)
                if len(set(m.labels_)) > 1: candidates.append((silhouette_score(z,m.labels_),m))
            self.score,self.model=max(candidates,key=lambda p:p[0]) if candidates else (None,KMeans(n_clusters=1,random_state=42,n_init=10).fit(z))
        distances=np.linalg.norm(z-self.model.cluster_centers_[self.model.labels_],axis=1)
        for c in range(self.model.n_clusters):
            vals=distances[self.model.labels_==c]
            self.thresholds[c]=max(float(np.percentile(vals,self.percentile)),1e-9)
        return self
    def predict(self,row,model_version=None):
        if self.model is None: raise RuntimeError("el modelo debe entrenarse antes de inferir")
        z=self.scaler.transform(np.asarray([row],dtype=float)); c=int(self.model.predict(z)[0]); d=float(np.linalg.norm(z[0]-self.model.cluster_centers_[c])); return Prediction(c,d,self.thresholds[c],model_version)
