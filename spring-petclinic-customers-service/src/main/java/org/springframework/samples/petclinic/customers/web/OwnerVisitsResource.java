package org.springframework.samples.petclinic.customers.web;

import jakarta.validation.constraints.Min;
import org.springframework.samples.petclinic.customers.model.Owner;
import org.springframework.samples.petclinic.customers.model.OwnerRepository;
import org.springframework.samples.petclinic.customers.model.Pet;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

import static java.util.stream.Collectors.groupingBy;

@RequestMapping("/owners")
@RestController
class OwnerVisitsResource {

    private final OwnerRepository ownerRepository;
    private final VisitsServiceClient visitsServiceClient;

    OwnerVisitsResource(OwnerRepository ownerRepository, VisitsServiceClient visitsServiceClient) {
        this.ownerRepository = ownerRepository;
        this.visitsServiceClient = visitsServiceClient;
    }

    @GetMapping("/{ownerId}/visits")
    public OwnerVisitsResponse getOwnerVisits(@PathVariable("ownerId") @Min(1) int ownerId) {
        Owner owner = ownerRepository.findById(ownerId)
            .orElseThrow(() -> new ResourceNotFoundException("Owner " + ownerId + " not found"));

        List<Pet> pets = owner.getPets();
        List<Integer> petIds = pets.stream().map(Pet::getId).toList();

        Map<Integer, List<VisitResponse>> visitsByPetId = visitsServiceClient.getVisitsForPets(petIds).stream()
            .collect(groupingBy(VisitResponse::petId));

        List<PetVisitsResponse> petResponses = pets.stream()
            .map(pet -> new PetVisitsResponse(
                pet.getId(), pet.getName(), pet.getBirthDate(), pet.getType(),
                visitsByPetId.getOrDefault(pet.getId(), List.of())))
            .toList();

        return new OwnerVisitsResponse(owner.getId(), owner.getFirstName(), owner.getLastName(),
            owner.getAddress(), owner.getCity(), owner.getTelephone(), petResponses);
    }
}
